package com.example.backend.work.dto;

import com.example.backend.work.entity.WarningSeverity;
import com.example.backend.work.entity.WarningSource;

import java.time.LocalDateTime;

/** 작업 경고. acknowledgedAt 이 null 이면 아직 확인하지 않음 (작업허가 승인 요청 전에 확인 필요) */
public record WorkWarningResponse(Long warningId, Long workId, WarningSource sourceType, Long nearMissId,
                                  WarningSeverity severity, String message, LocalDateTime shownAt,
                                  Long acknowledgedBy, String acknowledgedByName, LocalDateTime acknowledgedAt,
                                  String ackNote) {
}
