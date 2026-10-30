package com.example.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 에이전트 전용 실행기: 크기 제한, 넘치면 예외 없이 거절(로그), 잘못된 설정은 기동 실패 */
@DisplayName("에이전트 전용 실행기")
class AsyncConfigTest {

    @Test
    @DisplayName("스레드·대기열이 모두 차면 예외를 던지지 않고 그 작업만 받지 않는다")
    void overflow_isRejectedWithoutException() throws Exception {
        ThreadPoolTaskExecutor executor = new AsyncConfig().agentExecutor(1, 1, 0);
        executor.initialize();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        Runnable blocking = () -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ran.incrementAndGet();
        };
        try {
            executor.execute(blocking);   // 실행 중
            executor.execute(blocking);   // 대기열
            executor.execute(blocking);   // 넘침 → 거절, 예외 없음

            assertThat(executor.getThreadPoolExecutor().getMaximumPoolSize()).isEqualTo(1);
            assertThat(executor.getThreadPoolExecutor().getQueue()).hasSize(1);
        } finally {
            release.countDown();
            executor.shutdown();
            executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS);
        }
        assertThat(ran.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("스레드 수가 1 미만이거나 음수 설정이면 서버가 뜨지 않는다")
    void badConfig_failsFast() {
        assertThatThrownBy(() -> new AsyncConfig().agentExecutor(0, 10, 10)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AsyncConfig().agentExecutor(4, -1, 10)).isInstanceOf(IllegalStateException.class);
    }
}
