package com.example.backend.agent.client;

/**
 * Spring → AI 서버 아차사고 태그 제안 요청 (ai-model POST /api/v1/agent/near-miss-tag, NearMissTagRequest).
 * 글자 수는 AI 서버 제한에 맞춰 잘라서 보낸다 (title 300, description 4000, immediateAction 2000, zoneName 300).
 * workType 은 관련 작업의 작업 종류(GENERAL·HOT·CONFINED·HEIGHT), 관련 작업이 없으면 null.
 */
public record NearMissTagCall(Long runId, Long siteId, Long nearMissId, String title, String description,
                              String immediateAction, String zoneName, String workType) {
}
