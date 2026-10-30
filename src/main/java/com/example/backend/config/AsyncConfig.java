package com.example.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 요청 스레드와 분리한 비동기 처리 (@Async).
 * 에이전트 실행은 AI 응답을 최대 read-timeout 만큼 기다리므로 크기를 제한한 전용 실행기를 쓴다.
 *  - 스레드·대기열 크기는 app.agent.executor.* (yaml)
 *  - 대기열까지 차면 받지 않고 경고 로그만 남긴다. 그 실행은 PROCESSING 으로 남았다가
 *    멈춘 실행 정리(AgentRunSweeper)가 AGENT_TIMEOUT 으로 정리한다 (요청 스레드에 예외를 던지지 않게)
 *  - 서버 종료 시 진행 중인 실행이 끝나기를 await-termination-seconds 까지 기다린다
 * 이름 없는 @Async(OCR)도 이 실행기를 함께 쓴다 (OCR 은 제거 예정).
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String AGENT_EXECUTOR = "agentExecutor";

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean(name = AGENT_EXECUTOR)
    ThreadPoolTaskExecutor agentExecutor(@Value("${app.agent.executor.pool-size}") int poolSize,
                                         @Value("${app.agent.executor.queue-capacity}") int queueCapacity,
                                         @Value("${app.agent.executor.await-termination-seconds}") int awaitSeconds) {
        if (poolSize < 1 || queueCapacity < 0 || awaitSeconds < 0) {
            throw new IllegalStateException("app.agent.executor 설정이 올바르지 않습니다 (pool-size ≥ 1, queue-capacity ≥ 0).");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("agent-");
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setRejectedExecutionHandler((task, pool) ->
                log.warn("에이전트 실행 대기열이 가득 차 처리하지 못했습니다. 멈춘 실행 정리가 실패로 바꿉니다. "
                        + "active={} queued={}", pool.getActiveCount(), pool.getQueue().size()));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitSeconds);
        return executor;
    }
}
