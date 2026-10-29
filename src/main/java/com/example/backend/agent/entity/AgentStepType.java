package com.example.backend.agent.entity;

/** 단계 종류 (DB ck_as_type). TOOL_CALL 일 때만 tool_name 이 있다 */
public enum AgentStepType {
    PLAN, TOOL_CALL, SUFFICIENCY, JUDGE, VERIFY, OUTPUT, ASK_USER
}
