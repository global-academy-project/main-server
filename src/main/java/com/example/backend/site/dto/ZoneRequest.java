package com.example.backend.site.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 작업구역 등록·수정. code 는 현장 안에서 겹칠 수 없다. active=false 면 목록에서 빠진다 (수정 때만, 비우면 그대로) */
public record ZoneRequest(@NotBlank @Size(max = 30) String code, @NotBlank @Size(max = 100) String name,
                          @Size(max = 30) String zoneType, boolean confined, @Size(max = 500) String hazardNote,
                          Boolean active) {
}
