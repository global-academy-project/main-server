package com.example.backend.stt.dto;

/** 음성 입력 사용 여부와 제한 (화면이 마이크 버튼 표시·녹음 시간에 쓴다) */
public record SttStatusResponse(boolean enabled, int maxSeconds, long maxBytes) {
}
