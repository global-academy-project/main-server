package com.example.backend.nearmiss.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 자동 태깅 시작. lockVersion = 화면이 들고 있는 보고 버전 (다르면 409) */
public record AutoTagRequest(@NotNull @Min(0) Integer lockVersion) {
}
