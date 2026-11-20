package com.example.backend.agent.service;

import com.example.backend.agent.entity.*;
import com.example.backend.agent.repository.AgentConversationRepository;
import com.example.backend.agent.repository.AgentMessageRepository;
import com.example.backend.agent.repository.AgentRunRepository;
import com.example.backend.agent.repository.AgentStepRepository;
import com.example.backend.agent.repository.EvidenceCitationRepository;
import com.example.backend.audit.service.AuditReportService;
import com.example.backend.rule.repository.RuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 서버 결과를 한 트랜잭션에 저장한다: 실행 상태 + 단계 + 근거 + 답변 메시지.
 * 리스너와 분리된 빈이어야 @Transactional 이 적용된다.
 * 응답 전체는 agent_runs.output_json 에 그대로 남긴다 (slots·drafts·approvalGate 를 화면과 다음 요청이 그대로 쓴다).
 */
@Service
public class AgentResultWriter {

    private static final Logger log = LoggerFactory.getLogger(AgentResultWriter.class);
    private static final String WAITING = "WAITING_INPUT";
    private static final String DONE = "DONE";
    /** route.target: 감사 대응 보고서로 이어짐. agent_runs.target_type 에도 이 값으로 연결한다 (새 칼럼 없음) */
    public static final String AUDIT_TARGET = "AUDIT_REPORT";
    /** evidence_citations.kb_collection 허용 값 (DB ck_ev_kb_coll) */
    private static final java.util.Set<String> KB_COLLECTIONS = java.util.Set.of("kb_accidents", "kb_guides");

    private final AgentRunRepository runRepository;
    private final AgentStepRepository stepRepository;
    private final EvidenceCitationRepository citationRepository;
    private final AgentConversationRepository conversationRepository;
    private final AgentMessageRepository messageRepository;
    private final RuleRepository ruleRepository;
    private final AgentDraftWriter draftWriter;
    private final EvidenceTargetLookup evidenceLookup;
    private final AuditReportService auditReportService;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public AgentResultWriter(AgentRunRepository runRepository, AgentStepRepository stepRepository,
                             EvidenceCitationRepository citationRepository,
                             AgentConversationRepository conversationRepository,
                             AgentMessageRepository messageRepository, RuleRepository ruleRepository,
                             AgentDraftWriter draftWriter, EvidenceTargetLookup evidenceLookup,
                             AuditReportService auditReportService, JsonMapper jsonMapper, Clock clock) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.citationRepository = citationRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.ruleRepository = ruleRepository;
        this.draftWriter = draftWriter;
        this.evidenceLookup = evidenceLookup;
        this.auditReportService = auditReportService;
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
        if (run.getTaskType() == AgentTaskType.RECORD_QUERY) {
            saveRecordQuery(run, data, elapsedMs);
            return;
        }
        WorkRegisterData d = jsonMapper.treeToValue(data, WorkRegisterData.class);
        if (!WAITING.equals(d.runStatus()) && !DONE.equals(d.runStatus())) {
            failRun(run, "AI_BAD_RESPONSE: 알 수 없는 실행 상태 " + d.runStatus(), d.steps(), elapsedMs);
            return;
        }

        boolean waiting = WAITING.equals(d.runStatus());
        if (!waiting && d.route() != null && AUDIT_TARGET.equals(d.route().target())) {
            saveAuditRoute(run, d, data, elapsedMs);
            return;
        }
        // 모르는 route.target 은 일반 완료로 처리한다 (B 감사 분기 안내)

        // DONE 이면 초안을 업무 DB 에 작성 중(DRAFT)으로 저장. 초안 값이 맞지 않으면 아무것도 저장하지 않고 실패
        SavedDrafts saved = SavedDrafts.nothing();
        if (!waiting) {
            try {
                saved = draftWriter.save(run, data, permitIdOf(run));
            } catch (AgentDraftWriter.DraftInvalidException e) {
                failRun(run, "AI_BAD_RESPONSE: 초안을 저장할 수 없습니다. " + e.getMessage(), d.steps(), elapsedMs);
                return;
            }
        }

        int seq = saveSteps(run.getId(), d.steps());
        Map<String, Object> last = new LinkedHashMap<>();
        if (waiting) {
            last.put("ask", d.ask());
        } else {
            last.put("approvalGate", d.approvalGate());
        }
        stepRepository.save(new AgentStep(run.getId(), seq, waiting ? AgentStepType.ASK_USER : AgentStepType.OUTPUT,
                null, null, jsonMapper.writeValueAsString(last), AgentStepStatus.DONE, null));
        saveCitations(run.getId(), d.citations());

        String output = jsonMapper.writeValueAsString(withSaved(data, saved));
        if (!saved.isEmpty()) {
            run.linkTarget("WORK", saved.workId());
        }
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

    // ------------------------------------------------------------------ 감사 분기

    /**
     * 작업 등록 대화의 감사 분기 (AI 서버 route.target=AUDIT_REPORT, drafts 없음): 초안을 저장하지 않고 감사 대응 보고서를 만든다.
     * 권한·기간·미래 날짜·같은 보고서 확인은 AuditReportService.createFromChat 이 예외 없이 결과로 돌려준다.
     * 보고서를 만들거나 이미 있으면 실행을 그 보고서에 연결(target_type=AUDIT_REPORT)하고, 거절이면 연결 없이 사유를 답한다.
     * 실행은 어느 경우든 DONE. 만들 때 예상하지 못한 오류는 그대로 올라가 실행 전체가 실패로 정리된다 (한 트랜잭션).
     */
    private void saveAuditRoute(AgentRun run, WorkRegisterData d, JsonNode data, int elapsedMs) {
        WorkRegisterData.Route route = d.route();
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(route.dateFrom());
            to = LocalDate.parse(route.dateTo());
        } catch (DateTimeParseException | NullPointerException e) {
            failRun(run, "AI_BAD_RESPONSE: 감사 대응 보고서 기간을 읽을 수 없습니다.", d.steps(), elapsedMs);
            return;
        }
        AuditReportService.ChatResult result = auditReportService.createFromChat(run.getUserId(), run.getSiteId(), from, to);

        int seq = saveSteps(run.getId(), d.steps());
        Map<String, Object> last = new LinkedHashMap<>();
        last.put("route", route);
        last.put("auditReportId", result.reportId());
        last.put("created", result.created());
        last.put("refusal", result.refusal());
        stepRepository.save(new AgentStep(run.getId(), seq, AgentStepType.OUTPUT, null, null,
                jsonMapper.writeValueAsString(last), AgentStepStatus.DONE, null));

        if (result.reportId() != null) {
            run.linkTarget(AUDIT_TARGET, result.reportId());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        run.complete(jsonMapper.writeValueAsString(data), null, null, elapsedMs, null, null, now);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runStatus", d.runStatus());
        summary.put("route", AUDIT_TARGET);
        summary.put("auditReportId", result.reportId());
        addAssistantMessage(run, auditAnswer(route, result), jsonMapper.writeValueAsString(summary), now);
    }

    /** 감사 분기 답변 문장: 거절 사유 / 이미 있는 보고서 안내 / 만들 때 AI 문구(비어 있으면 기본 문구) */
    static String auditAnswer(WorkRegisterData.Route route, AuditReportService.ChatResult result) {
        if (result.refusal() != null) {
            return result.refusal();
        }
        if (!result.created()) {
            return "같은 기간의 감사 대응 보고서가 이미 있어요. 감사 대응 화면에서 확인해 주세요.";
        }
        String message = cut(route.message(), 4000);
        return message != null ? message
                : route.dateFrom() + " ~ " + route.dateTo() + " 기간의 감사 대응 보고서를 만들게요. 완료되면 감사 대응 화면에서 확인할 수 있어요.";
    }

    // ------------------------------------------------------------------ 기록 질문

    /**
     * 기록 질문 결과: 단계 + 마지막 단계(조회 조건) + 근거 기록(그 현장에 실제로 있는 것만) + 답변 메시지.
     * 업무 기록은 바꾸지 않는다 (읽기만 하는 실행).
     */
    private void saveRecordQuery(AgentRun run, JsonNode data, int elapsedMs) {
        RecordQueryData d = jsonMapper.treeToValue(data, RecordQueryData.class);
        if (!WAITING.equals(d.runStatus()) && !DONE.equals(d.runStatus())) {
            failRun(run, "AI_BAD_RESPONSE: 알 수 없는 실행 상태 " + d.runStatus(), d.steps(), elapsedMs);
            return;
        }
        boolean waiting = WAITING.equals(d.runStatus());
        String answer = waiting
                ? (d.ask() != null && d.ask().message() != null && !d.ask().message().isBlank()
                        ? d.ask().message() : "질문을 조금 더 구체적으로 알려 주세요.")
                : (d.answer() != null && !d.answer().isBlank() ? d.answer().trim() : null);
        if (answer == null) {
            failRun(run, "AI_BAD_RESPONSE: 답변이 비어 있습니다.", d.steps(), elapsedMs);
            return;
        }

        int seq = saveSteps(run.getId(), d.steps());
        Map<String, Object> last = new LinkedHashMap<>();
        last.put("queries", d.queries());
        if (waiting) {
            last.put("ask", d.ask());
        }
        stepRepository.save(new AgentStep(run.getId(), seq, waiting ? AgentStepType.ASK_USER : AgentStepType.OUTPUT,
                null, null, jsonMapper.writeValueAsString(last), AgentStepStatus.DONE, null));
        saveRecordEvidence(run, d.evidence());

        LocalDateTime now = LocalDateTime.now(clock);
        String output = jsonMapper.writeValueAsString(data);
        if (waiting) {
            run.waitForInput(output, null, null, elapsedMs, null, null);
        } else {
            run.complete(output, null, null, elapsedMs, null, null, now);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runStatus", d.runStatus());
        summary.put("queries", d.queries());
        addAssistantMessage(run, cut(answer, 4000), jsonMapper.writeValueAsString(summary), now);
    }

    /** 근거 기록: 그 현장에 실제로 있는 기록만 RECORD 근거로 저장 (AI 가 만든 id 를 그대로 믿지 않음, 응답 원본은 output_json 에 남음) */
    private void saveRecordEvidence(AgentRun run, List<RecordQueryData.EvidenceItem> evidence) {
        if (evidence == null) {
            return;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (RecordQueryData.EvidenceItem e : evidence) {
            if (e == null || !seen.add(e.type() + ":" + e.id())) {
                continue;
            }
            if (!evidenceLookup.exists(e.type(), e.id(), run.getSiteId())) {
                log.info("이 현장에 없는 근거 기록이라 저장하지 않습니다. runId={} type={} id={}", run.getId(), e.type(), e.id());
                continue;
            }
            String title = cut(e.label() != null && !e.label().isBlank() ? e.label() : e.type() + " " + e.id(), 300);
            citationRepository.save(EvidenceCitation.of(run.getId(), null, EvidenceSourceType.RECORD, title,
                    cut(e.type() + ":" + e.id(), 100), null, null).forTarget(e.type(), e.id()));
        }
    }

    // ------------------------------------------------------------------ 내부

    /** 실행 요청에 실린 허가서 id (대화가 허가서에 묶여 있을 때) */
    private Long permitIdOf(AgentRun run) {
        if (run.getInputJson() == null) {
            return null;
        }
        return jsonMapper.readValue(run.getInputJson(), RunInput.class).permitId();
    }

    /** AI 응답 원본에 저장 결과("saved")를 붙인다. 화면이 저장된 작업·허가서로 이동할 수 있게 */
    private JsonNode withSaved(JsonNode data, SavedDrafts saved) {
        if (saved.isEmpty() || !(data instanceof ObjectNode object)) {
            return data;
        }
        ObjectNode copy = object.deepCopy();
        copy.set("saved", jsonMapper.valueToTree(saved));
        return copy;
    }

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
