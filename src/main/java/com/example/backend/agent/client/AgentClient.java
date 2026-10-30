package com.example.backend.agent.client;

/**
 * AI 서버(에이전트) 호출. app.agent.client 로 구현을 고른다 (mock | fastapi).
 * 실패는 AgentCallException. 오래 걸리므로 트랜잭션 밖(커밋 후 비동기)에서만 부른다.
 */
public interface AgentClient {

    AgentReply workRegister(WorkRegisterCall call);

    /** 기록 질문 (RECORD_QUERY). 응답 모양은 RecordQueryData */
    AgentReply recordQuery(RecordQueryCall call);
}
