package com.example.backend.agent.entity;

/** 대화 메시지 역할 (DB ck_msg_role). TOOL 은 tool_name·tool_call_id 필수 */
public enum MessageRole {
    USER, ASSISTANT, TOOL
}
