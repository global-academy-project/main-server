package com.example.backend.agent.client;

/**
 * AI 서버(에이전트) 호출. app.agent.client 로 구현을 고른다 (mock | fastapi).
 * 실패는 AgentCallException. 오래 걸리므로 트랜잭션 밖(커밋 후 비동기)에서만 부른다.
 */
public interface AgentClient {

    AgentReply workRegister(WorkRegisterCall call);

    /** 기록 질문 (RECORD_QUERY). 응답 모양은 RecordQueryData */
    AgentReply recordQuery(RecordQueryCall call);

    /**
     * 감사보고서 요약·수치 검증 (ai-model POST /api/v1/agent/audit-report).
     * body 는 ai-model AuditReportInput 모양 (reportId, siteId, dateFrom, dateTo, items[{queryType, title, stats, records, evidence}]).
     * 응답 data 는 AuditReportResult (status, items[{queryType, title, summary, verification}], overallVerification)
     */
    AgentReply auditReport(java.util.Map<String, Object> body);
}
