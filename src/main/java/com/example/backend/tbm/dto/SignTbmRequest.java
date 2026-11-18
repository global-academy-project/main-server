package com.example.backend.tbm.dto;

import jakarta.validation.constraints.NotNull;

/** TBM 참석자 서명: 서명한 참석 작업자 (계정 없는 작업자, 현장에서 TBM 진행자 기기로 받는다) */
public record SignTbmRequest(@NotNull Long workerId) {
}
