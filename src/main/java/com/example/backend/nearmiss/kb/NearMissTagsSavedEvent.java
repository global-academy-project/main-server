package com.example.backend.nearmiss.kb;

/** 사람이 아차사고 태그를 저장함 (커밋 뒤 RAG 맞추기: 태그가 있으면 적재, 모두 지웠으면 삭제) */
public record NearMissTagsSavedEvent(Long nearMissId) {
}
