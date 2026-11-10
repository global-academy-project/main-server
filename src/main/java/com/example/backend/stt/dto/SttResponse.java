package com.example.backend.stt.dto;

/** 받아 적은 글. 화면은 입력칸 글 뒤에 붙이고, 사람이 확인한 뒤 "칸 채우기"로 넘긴다 */
public record SttResponse(String text, String model) {
}
