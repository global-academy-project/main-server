package com.example.backend.agent.service;

import com.example.backend.agent.entity.*;
import com.example.backend.agent.repository.AgentConversationRepository;
import com.example.backend.agent.repository.AgentMessageRepository;
import com.example.backend.agent.repository.AgentRunRepository;
import com.example.backend.agent.repository.AgentStepRepository;
import com.example.backend.agent.repository.EvidenceCitationRepository;
import com.example.backend.rule.repository.RuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 서버 결과를 한 트랜잭션에 저장한다: 실행 상태 + 단계 + 근거 + 답변 메시지.
 * 리스너와 분리된 빈이어야 @Transactional 이 적용된다 (OCR 과 같은 구조).
 * 응답 전체는 agent_runs.output_json 에 그대로 남긴다 (slots·drafts·approvalGate 를 화면과 다음 요청이 그대로 쓴다).
 */
@Service
public class AgentResultWriter {

    private static final Logger log = LoggerFactory.getLogger(AgentResultWriter.class);
    private static final String WAITING = "WAITING_INPUT";
    private static final String DONE = "DONE";
    /** evidence_citations.kb_collection 허용 값 (DB ck_ev_kb_coll) */
    private static final java.util.Set<String> KB_COLLECTIONS = java.util.Set.of("kb_accidents", "kb_guides");

    private final AgentRunRepository runRepository;
    private final AgentStepRepository stepRepository;
    private final EvidenceCitationRepository citationRepository;
    private final AgentConversationRepository conversationRepository;
    private final AgentMessageRepository messageRepository;
    private final RuleRepository ruleRepository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public AgentResultWriter(AgentRunRepository runRepository, AgentStepRepository stepRepository,
                             EvidenceCitationRepository citationRepository,
                             AgentConversationRepository conversationRepository,
                             AgentMessageRepository messageRepository, RuleRepository ruleRepository,
                             JsonMapper jsonMapper, Clock clock) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.citationRepository = citationRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.ruleRepository = ruleRepository;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Transactional
    public void saveSuccess(Long runId, JsonNode data, int elapsedMs) {
        AgentRun run = runRepository.findForUpdate(runId).orElse(null);
        if (run == null || run.getStatus() != AgentRunStatus.PROCESSING) {
            log.warn("이미 끝났거나 없는 실행의 결과는 저장하지 않습니다. runId={}", runId);
            return;
        }
        WorkRegisterData d = jsonMapper.treeToValue(data, WorkRegisterData.class);
        if (!WAITING.equals(d.runStatus()) && !DONE.equals(d.runStatus())) {
            failRun(run, "AI_BAD_RESPONSE: 알 수 없는 실행 상태 " + d.runStatus(), d.steps(), elapsedMs);
            return;
        }

        int seq = saveSteps(run.getId(), d.steps());
        boolean waiting = WAITING.equals(d.runStatus());
        Map<String, Object> last = new LinkedHashMap<>();
        if (waiting) {
            last.put("ask", d.ask());
        } else {
            last.put("approvalGate", d.approvalGate());
        }
        stepRepository.save(new AgentStep(run.getId(), seq, waiting ? AgentStepType.ASK_USER : AgentStepType.OUTPUT,
                null, null, jsonMapper.writeValueAsString(last), AgentStepStatus.DONE, null));
        saveCitations(run.getId(), d.citations());

        String output = jsonMapper.writeValueAsString(data);
        LocalDateTime now = LocalDateTime.now(clock);
        if (waiting) {
            run.waitForInput(output, null, null, elapsedMs, null, null);
        } else {
            run.complete(output, null, null, elapsedMs, null, null, now);
        }
        addAssistantMessage(run, answerOf(d, waiting), summaryJson(d), now);
    }

    @Transactional
    public void saveFailure(Long runId, String code, String message, JsonNode steps, int elapsedMs) {
        AgentRun run = runRepository.findForUpdate(runId).orElse(null);
        if (run == null || run.getStatus() != AgentRunStatus.PROCESSING) {
            log.warn("이미 끝났거나 없는 실행의 실패는 저장하지 않습니다. runId={}", runId);
            return;
        }
        List<WorkRegisterData.StepItem> items = null;
        if (steps != null && steps.isArray()) {
            try {
                items = List.of(jsonMapper.treeToValue(steps, WorkRegisterData.StepItem[].class));
            } catch (RuntimeException e) {
                log.warn("실패 응답의 steps 를 읽지 못했습니다. runId={}", runId);
            }
        }
        failRun(run, code + ": " + message, items, elapsedMs);
    }

    // ------------------------------------------------------------------ 내부

    private void failRun(AgentRun run, String error, List<WorkRegisterData.StepItem> steps, int elapsedMs) {
        saveSteps(run.getId(), steps);
        LocalDateTime now = LocalDateTime.now(clock);
        run.fail(error, elapsedMs, now);
        addAssistantMessage(run, "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.", null, now);
    }

    /** 단계 저장. 다음 순번을 돌려준다 (저장하지 않는 단계는 건너뛰고 순번을 1부터 다시 매김) */
    private int saveSteps(Long runId, List<WorkRegisterData.StepItem> steps) {
        int seq = 1;
        if (steps == null) {
            return seq;
        }
        for (WorkRegisterData.StepItem s : steps) {
            AgentStepType type = AgentStepMapping.typeOf(s.kind(), s.name(), s.toolName());
            if (type == null) {
                if (!"evidence.search".equals(s.name())) {
                    log.info("저장하지 않는 단계: kind={} name={}", s.kind(), s.name());
                }
                continue;
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("name", s.name());
            output.put("status", s.status());
            output.put("output", s.output());
            if (s.errorCode() != null) {
                output.put("errorCode", s.errorCode());
            }
            stepRepository.save(new AgentStep(runId, seq++, type,
                    type == AgentStepType.TOOL_CALL ? s.toolName() : null,
                    s.input() == null ? null : jsonMapper.writeValueAsString(s.input()),
                    jsonMapper.writeValueAsString(output),
                    AgentStepMapping.statusOf(s.status()), s.elapsedMs()));
        }
        return seq;
    }

    /** 근거 저장. 저장할 수 없는 근거(규칙 코드 없음, KB 출처 정보 없음 등)는 건너뛴다 (응답 전체는 output_json 에 남음) */
    private void saveCitations(Long runId, List<WorkRegisterData.CitationItem> citations) {
        if (citations == null) {
            return;
        }
        for (WorkRegisterData.CitationItem c : citations) {
            try {
                EvidenceCitation e = toCitation(runId, c);
                if (e != null) {
                    citationRepository.save(e);
                }
            } catch (IllegalArgumentException ex) {
                log.warn("근거를 저장하지 못했습니다. runId={} kind={} reason={}", runId, c.kind(), ex.getMessage());
            }
        }
    }

    private EvidenceCitation toCitation(Long runId, WorkRegisterData.CitationItem c) {
        String title = cut(c.source() == null || c.source().isBlank() ? c.refId() : c.source(), 300);
        if (title == null) {
            return null;
        }
        switch (c.kind() == null ? "" : c.kind()) {
            case "RULE" -> {
                return ruleRepository.findFirstByRuleCodeOrderByVersionDesc(c.refId())
                        .map(rule -> EvidenceCitation.fromRule(runId, null, rule.getId(), title,
                                cut(rule.getLegalBasis(), 100), rule.getSourceUrl(), c.snippet()))
                        .orElseGet(() -> {
                            log.warn("근거의 규칙 코드를 찾을 수 없습니다: {}", c.refId());
                            return null;
                        });
            }
            case "MSDS" -> {
                return EvidenceCitation.of(runId, null, EvidenceSourceType.MSDS, title, cut(c.refId(), 100), null, c.snippet());
            }
            case "KB" -> {
                EvidenceSourceType type = sourceTypeOf(c.sourceType());
                if (type == null || !type.requiresKbChunk() || !KB_COLLECTIONS.contains(c.kbCollection())) {
                    log.info("KB 근거의 출처 종류·컬렉션이 없거나 허용 값이 아니라 저장하지 않습니다 (output_json 에는 남음). refId={}", c.refId());
                    return null;
                }
                return EvidenceCitation.fromKb(runId, null, type, c.kbCollection(), c.refId(), title, null, null, c.snippet());
            }
            default -> {
                log.info("알 수 없는 근거 종류: {}", c.kind());
                return null;
            }
        }
    }

    private static EvidenceSourceType sourceTypeOf(String value) {
        if (value == null) return null;
        try {
            return EvidenceSourceType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void addAssistantMessage(AgentRun run, String content, String contentJson, LocalDateTime now) {
        if (run.getConversationId() == null) {
            return;   // 자동 실행(대화 없음)
        }
        AgentConversation conv = conversationRepository.findForUpdate(run.getConversationId()).orElse(null);
        if (conv == null) {
            return;
        }
        messageRepository.save(AgentMessage.assistant(conv.getId(), conv.nextSeq(now), content, contentJson, run.getId()));
    }

    /** 대화에 보일 답변 문장 */
    static String answerOf(WorkRegisterData d, boolean waiting) {
        if (waiting) {
            return d.ask() != null && d.ask().message() != null && !d.ask().message().isBlank()
                    ? d.ask().message() : "추가로 알려 주실 내용이 있습니다.";
        }
        WorkRegisterData.ApprovalGate gate = d.approvalGate();
        if (gate != null && Boolean.TRUE.equals(gate.canRequest())) {
            return "초안을 만들었습니다. 내용을 확인한 뒤 승인 요청할 수 있습니다.";
        }
        List<String> reasons = gate == null || gate.reasons() == null ? List.of() : gate.reasons();
        return reasons.isEmpty()
                ? "초안을 만들었습니다. 승인 요청 전에 확인할 것이 있습니다."
                : "초안을 만들었습니다. 승인 요청 전에 확인할 것: " + String.join(" / ", reasons);
    }

    private String summaryJson(WorkRegisterData d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runStatus", d.runStatus());
        m.put("approvalGate", d.approvalGate());
        return jsonMapper.writeValueAsString(m);
    }

    private static String cut(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
