package com.example.backend.agent.service;

import com.example.backend.agent.dto.AgentMessageItem;
import com.example.backend.agent.dto.AgentRunDetail;
import com.example.backend.agent.dto.AgentRunStarted;
import com.example.backend.agent.dto.StartWorkRegisterRequest;
import com.example.backend.agent.entity.*;
import com.example.backend.agent.repository.*;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.common.type.NetworkMode;
import com.example.backend.permit.entity.Permit;
import com.example.backend.permit.repository.PermitRepository;
import com.example.backend.site.entity.Site;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.site.service.SiteGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 에이전트 실행 시작과 조회 (작업 등록 대화).
 * 흐름: ① 한 트랜잭션에서 대화·사용자 메시지·실행(PROCESSING) 저장 + 이벤트 → ② 커밋 후 AI 서버 호출 (리스너)
 *      → ③ 결과 저장 (AgentResultWriter). 화면은 실행 상태를 조회(폴링)한다.
 * 대화는 만든 사람만 본다. 현장 권한이 없어지면 그 현장 대화도 못 본다.
 * 응답이 끝내 오지 않은 실행(서버 재시작, 비동기 작업 유실)은 stale-after-seconds 가 지나면 실패로 정리한다
 * (여기서 다음 메시지를 받을 때, 그리고 AgentRunSweeper 가 주기적으로).
 */
@Service
public class AgentRunService {

    private final AgentConversationRepository conversationRepository;
    private final AgentMessageRepository messageRepository;
    private final AgentRunRepository runRepository;
    private final AgentStepRepository stepRepository;
    private final EvidenceCitationRepository citationRepository;
    private final SiteRepository siteRepository;
    private final SiteGuard siteGuard;
    private final PermitRepository permitRepository;
    private final AgentResultWriter writer;
    private final ApplicationEventPublisher events;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final NetworkMode networkMode;
    private final Duration staleAfter;

    public AgentRunService(AgentConversationRepository conversationRepository, AgentMessageRepository messageRepository,
                           AgentRunRepository runRepository, AgentStepRepository stepRepository,
                           EvidenceCitationRepository citationRepository, SiteRepository siteRepository,
                           SiteGuard siteGuard, PermitRepository permitRepository, AgentResultWriter writer,
                           ApplicationEventPublisher events, JsonMapper jsonMapper, Clock clock,
                           @Value("${app.agent.network-mode}") NetworkMode networkMode,
                           @Value("${app.agent.stale-after-seconds}") long staleAfterSeconds) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.citationRepository = citationRepository;
        this.siteRepository = siteRepository;
        this.siteGuard = siteGuard;
        this.permitRepository = permitRepository;
        this.writer = writer;
        this.events = events;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.networkMode = networkMode;
        this.staleAfter = Duration.ofSeconds(staleAfterSeconds);
    }

    /** 작업 등록 대화 메시지. 새 대화면 만들고, 이어지는 대화면 직전 실행의 slots 를 그대로 이어 보낸다 */
    @Transactional
    public AgentRunStarted startWorkRegister(Long userId, StartWorkRegisterRequest r) {
        siteGuard.requireAccess(userId, r.siteId());
        Site site = siteRepository.findById(r.siteId()).orElseThrow(() -> new BusinessException(ErrorCode.SITE_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now(clock);
        String message = r.message().trim();
        if (r.permitId() != null) {
            requirePermitOfSite(r.permitId(), r.siteId());
        }

        AgentConversation conv;
        JsonNode slots = null;
        if (r.conversationId() == null) {
            conv = conversationRepository.saveAndFlush(new AgentConversation(r.siteId(), userId, message, null, null));
        } else {
            conv = conversationRepository.findForUpdate(r.conversationId())
                    .filter(c -> c.getUserId().equals(userId) && c.getSiteId().equals(r.siteId()) && c.getDeletedAt() == null)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "대화를 찾을 수 없습니다."));
            List<AgentRun> runs = runRepository.findByConversationIdOrderByIdAsc(conv.getId());
            AgentRun last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
            if (last != null && last.getStatus() == AgentRunStatus.PROCESSING) {
                if (!isStale(last, now)) {
                    throw new BusinessException(ErrorCode.INVALID_STATE, "이전 메시지를 처리하고 있습니다. 끝난 뒤 다시 보내 주세요.");
                }
                failStale(last, now);   // 응답이 끝내 오지 않은 실행은 실패로 정리하고 이어서 받는다
            }
            slots = lastSlots(runs);
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", message);
        input.put("slots", slots);
        input.put("permitId", r.permitId());
        AgentRun run = runRepository.saveAndFlush(new AgentRun(r.siteId(), userId, conv.getId(),
                AgentTaskType.WORK_REGISTER, AgentMode.valueOf(site.getMode().name()), networkMode,
                jsonMapper.writeValueAsString(input)));
        messageRepository.save(AgentMessage.user(conv.getId(), conv.nextSeq(now), message, run.getId()));

        events.publishEvent(new AgentRunRequestedEvent(run.getId()));
        return new AgentRunStarted(conv.getId(), run.getId(), run.getStatus());
    }

    /** 실행 상태·결과 (만든 사람만) */
    @Transactional(readOnly = true)
    public AgentRunDetail getRun(Long userId, Long runId) {
        AgentRun run = runRepository.findById(runId)
                .filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "실행을 찾을 수 없습니다."));
        siteGuard.requireAccess(userId, run.getSiteId());

        JsonNode result = run.getOutputJson() == null ? null : jsonMapper.readTree(run.getOutputJson());
        List<AgentRunDetail.Step> steps = stepRepository.findByRunIdOrderBySeqAsc(run.getId()).stream()
                .map(s -> new AgentRunDetail.Step(s.getSeq(), s.getStepType(), s.getToolName(), s.getStatus(), s.getLatencyMs()))
                .toList();
        List<AgentRunDetail.Citation> citations = citationRepository.findByRunIdOrderByIdAsc(run.getId()).stream()
                .map(c -> new AgentRunDetail.Citation(c.getSourceType(), c.getTitle(), c.getLocator(), c.getUrl(), c.getSnippet()))
                .toList();
        Long permitId = run.getInputJson() == null ? null
                : longOrNull(jsonMapper.readTree(run.getInputJson()).path("permitId"));
        Long workId = "WORK".equals(run.getTargetType()) ? run.getTargetId() : null;
        return new AgentRunDetail(run.getId(), run.getConversationId(), run.getSiteId(), permitId, workId,
                run.getTaskType(), run.getStatus(), result,
                run.getErrorMessage(), run.getTotalMs(), steps, citations, run.getCreatedAt(), run.getFinishedAt());
    }

    private static Long longOrNull(JsonNode node) {
        return node.isIntegralNumber() ? node.asLong() : null;
    }

    /** 대화 메시지 목록 (만든 사람만). 도구 메시지는 화면에 보이지 않으므로 뺀다 */
    @Transactional(readOnly = true)
    public List<AgentMessageItem> getMessages(Long userId, Long conversationId) {
        AgentConversation conv = conversationRepository.findById(conversationId)
                .filter(c -> c.getUserId().equals(userId) && c.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "대화를 찾을 수 없습니다."));
        siteGuard.requireAccess(userId, conv.getSiteId());
        return messageRepository.findByConversationIdOrderBySeqAsc(conv.getId()).stream()
                .filter(m -> m.getRole() != MessageRole.TOOL)
                .map(m -> new AgentMessageItem(m.getId(), m.getSeq(), m.getRole(), m.getContent(), m.getRunId(), m.getCreatedAt()))
                .toList();
    }

    /** 응답 대기 시간이 지나도록 PROCESSING 인 실행인가 */
    boolean isStale(AgentRun run, LocalDateTime now) {
        return run.getCreatedAt() != null && run.getCreatedAt().plus(staleAfter).isBefore(now);
    }

    /** 멈춘 실행을 실패로 (같은 트랜잭션) */
    void failStale(AgentRun run, LocalDateTime now) {
        int elapsed = (int) Math.min(Integer.MAX_VALUE, Duration.between(run.getCreatedAt(), now).toMillis());
        writer.saveFailure(run.getId(), "AGENT_TIMEOUT", "AI 서버 응답이 없어 처리를 중단했습니다.", null, elapsed);
    }

    /** 판정을 저장할 허가서는 이 현장 것이고, 아직 고칠 수 있는 상태여야 한다 */
    private void requirePermitOfSite(Long permitId, Long siteId) {
        Permit permit = permitRepository.findById(permitId)
                .filter(p -> p.getSiteId().equals(siteId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "허가서를 찾을 수 없습니다."));
        if (!permit.getStatus().isEditable()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "승인 요청된 허가서는 다시 판정할 수 없습니다.");
        }
    }

    /** 직전에 끝난(되묻기·완료) 실행 응답의 slots. 실패한 실행은 건너뛴다 */
    private JsonNode lastSlots(List<AgentRun> runs) {
        for (int i = runs.size() - 1; i >= 0; i--) {
            AgentRun run = runs.get(i);
            if ((run.getStatus() == AgentRunStatus.WAITING_INPUT || run.getStatus() == AgentRunStatus.DONE)
                    && run.getOutputJson() != null) {
                JsonNode slots = jsonMapper.readTree(run.getOutputJson()).get("slots");
                return slots == null || slots.isNull() ? null : slots;
            }
        }
        return null;
    }
}
