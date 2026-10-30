package com.example.backend.agent.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동 시 실제로 선택된 AI 클라이언트를 로그로 남긴다.
 * 기본값이 mock 이라, 운영에서 APP_AGENT_CLIENT 가 빠지면 가짜 응답이 나간다 → mock 이면 경고로 드러낸다.
 */
@Component
public class AgentClientStartupLog {

    private static final Logger log = LoggerFactory.getLogger(AgentClientStartupLog.class);

    private final AgentClient client;
    private final String baseUrl;

    public AgentClientStartupLog(AgentClient client, @Value("${app.agent.base-url}") String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logSelectedClient() {
        if (client instanceof MockAgentClient) {
            log.warn("에이전트 AI 클라이언트: mock (가짜 응답). 실제 AI 서버를 쓰려면 APP_AGENT_CLIENT=fastapi 로 설정하세요.");
        } else {
            log.info("에이전트 AI 클라이언트: {} ({})", client.getClass().getSimpleName(), baseUrl);
        }
    }

    /** 지금 선택된 클라이언트가 mock 인지 (테스트·상태 확인용) */
    public boolean isMock() {
        return client instanceof MockAgentClient;
    }
}
