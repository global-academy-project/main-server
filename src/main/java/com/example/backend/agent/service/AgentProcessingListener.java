package com.example.backend.agent.service;

import com.example.backend.agent.client.AgentCallException;
import com.example.backend.agent.client.AgentClient;
import com.example.backend.agent.client.AgentReply;
import com.example.backend.agent.client.WorkRegisterCall;
import com.example.backend.agent.entity.AgentRun;
import com.example.backend.agent.entity.AgentRunStatus;
import com.example.backend.agent.repository.AgentRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * 커밋 이후(AFTER_COMMIT) 별도 스레드에서 AI 서버를 부르고, 저장은 AgentResultWriter 에 맡긴다.
 * 커밋 후에 부르는 이유: AI 서버가 check_rules 를 부르면 판정 기록이 agent_runs.id 를 외래키로 건다 (계획서 7-2장).
 * AI 호출은 오래 걸리므로(최대 app.agent.read-timeout-seconds) 트랜잭션 밖에서 한다.
 */
@Component
public class AgentProcessingListener {

    private static final Logger log = LoggerFactory.getLogger(AgentProcessingListener.class);

    private final AgentRunRepository runRepository;
    private final AgentClient agentClient;
    private final AgentResultWriter writer;
    private final JsonMapper jsonMapper;

    public AgentProcessingListener(AgentRunRepository runRepository, AgentClient agentClient,
                                   AgentResultWriter writer, JsonMapper jsonMapper) {
        this.runRepository = runRepository;
        this.agentClient = agentClient;
        this.writer = writer;
        this.jsonMapper = jsonMapper;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(AgentRunRequestedEvent event) {
        AgentRun run = runRepository.findById(event.runId()).orElse(null);
        if (run == null || run.getStatus() != AgentRunStatus.PROCESSING) {
            log.warn("처리할 실행이 없습니다. runId={}", event.runId());
            return;
        }
        long started = System.nanoTime();
        try {
            RunInput input = jsonMapper.readValue(run.getInputJson(), RunInput.class);
            AgentReply reply = agentClient.workRegister(new WorkRegisterCall(run.getId(), run.getSiteId(),
                    input.permitId(), input.message(), input.slots(), run.getMode().name()));
            writer.saveSuccess(run.getId(), reply.data(), elapsed(started));
        } catch (AgentCallException e) {
            log.warn("AI 서버 실행 실패. runId={} code={}", run.getId(), e.getCode());
            saveFailure(run.getId(), e.getCode(), e.getMessage(), e.getSteps(), started);
        } catch (Throwable e) {
            log.error("에이전트 실행 처리 실패. runId={}", run.getId(), e);
            saveFailure(run.getId(), "AGENT_FAILED", "실행 결과를 처리하지 못했습니다.", null, started);
        }
    }

    private void saveFailure(Long runId, String code, String message, tools.jackson.databind.JsonNode steps, long started) {
        try {
            writer.saveFailure(runId, code, message, steps, elapsed(started));
        } catch (Exception inner) {
            log.error("실패 상태 저장에 실패했습니다. runId={}", runId, inner);
        }
    }

    private static int elapsed(long startedNanos) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedNanos) / 1_000_000);
    }
}
