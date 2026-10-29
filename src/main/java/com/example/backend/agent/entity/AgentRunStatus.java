package com.example.backend.agent.entity;

/**
 * 실행 상태 (DB ck_ar_status).
 *  PROCESSING → DONE / FAILED (끝, finished_at 필수) 또는 WAITING_INPUT (되묻기, 답변은 같은 대화의 다음 실행)
 */
public enum AgentRunStatus {
    PROCESSING, WAITING_INPUT, DONE, FAILED
}
