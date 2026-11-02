package com.example.backend.worker.dto;

import java.time.LocalDate;

/** 현장 배치(date = 배치일)·현장 떠남(date = 떠난 날). 비우면 오늘 */
public record AssignmentRequest(LocalDate date) {
}
