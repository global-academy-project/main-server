package com.example.backend.agent.service;

import com.example.backend.agent.entity.AgentRun;
import com.example.backend.agent.entity.AgentRunStatus;
import com.example.backend.agent.repository.AgentRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 멈춘 실행 정리. 비동기 호출이 사라지거나(서버 재시작, 작업 거절) 응답이 끝내 오지 않아
 * stale-after-seconds 가 지나도록 PROCESSING 인 실행을 FAILED(AGENT_TIMEOUT)로 바꾼다.
 * 시작 시각 기준이라 같은 DB 를 쓰는 다른 서버가 처리 중인 실행은 건드리지 않는다
 * (정상 처리라면 응답 제한 시간 안에 끝나므로).
 */
@Component
public class AgentRunSweeper {

    private static final Logger log = LoggerFactory.getLogger(AgentRunSweeper.class);

    private final AgentRunRepository runRepository;
    private final AgentResultWriter writer;
    private final Clock clock;
    private final Duration staleAfter;

    public AgentRunSweeper(AgentRunRepository runRepository, AgentResultWriter writer, Clock clock,
                           @Value("${app.agent.stale-after-seconds}") long staleAfterSeconds,
                           @Value("${app.agent.connect-timeout-seconds}") long connectTimeoutSeconds,
                           @Value("${app.agent.read-timeout-seconds}") long readTimeoutSeconds) {
        // 정상 처리 중인 실행을 멈춘 것으로 잘못 정리하지 않게, 정리 기준은 AI 호출 최대 시간보다 길어야 한다
        if (staleAfterSeconds <= connectTimeoutSeconds + readTimeoutSeconds) {
            throw new IllegalStateException("app.agent.stale-after-seconds(" + staleAfterSeconds
                    + ")는 connect-timeout-seconds + read-timeout-seconds(" + (connectTimeoutSeconds + readTimeoutSeconds)
                    + ")보다 커야 합니다.");
        }
        this.runRepository = runRepository;
        this.writer = writer;
        this.clock = clock;
        this.staleAfter = Duration.ofSeconds(staleAfterSeconds);
    }

    @Scheduled(fixedDelayString = "${app.agent.sweep-interval-ms}")
    public void sweep() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<AgentRun> stale = runRepository.findByStatusAndCreatedAtBefore(AgentRunStatus.PROCESSING, now.minus(staleAfter));
        for (AgentRun run : stale) {
            try {
                int elapsed = (int) Math.min(Integer.MAX_VALUE, Duration.between(run.getCreatedAt(), now).toMillis());
                writer.saveFailure(run.getId(), "AGENT_TIMEOUT", "AI 서버 응답이 없어 처리를 중단했습니다.", null, elapsed);
            } catch (RuntimeException e) {
                log.warn("멈춘 실행 정리 실패. runId={}", run.getId(), e);
            }
        }
        if (!stale.isEmpty()) {
            log.warn("응답 없이 멈춘 실행을 실패로 정리했습니다. count={}", stale.size());
        }
    }
}
