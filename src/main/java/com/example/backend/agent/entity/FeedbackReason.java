package com.example.backend.agent.entity;

/** 싫어요 이유 (DB ck_fb_reason). DOWN 이면 필수 */
public enum FeedbackReason {
    WRONG_FACT, MISSING_HAZARD, BAD_CITATION, WRONG_RULE, FORMAT, TOO_SLOW, OTHER
}
