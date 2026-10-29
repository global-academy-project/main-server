package com.example.backend.corrective.entity;

/**
 * 개선 조치 상태 (DB ck_ca_status).
 * OPEN 등록 / IN_PROGRESS 조치 중 / DONE 완료 (완료 시각·내용 필수) / VERIFIED 확인 (확인자·시각 필수) / CANCELED 취소
 */
public enum ActionStatus {
    OPEN, IN_PROGRESS, DONE, VERIFIED, CANCELED;

    /** 아직 끝나지 않은 조치 (기한 지남 알림 대상) */
    public boolean isOpen() {
        return this == OPEN || this == IN_PROGRESS;
    }
}
