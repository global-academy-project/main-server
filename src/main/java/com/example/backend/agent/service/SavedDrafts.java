package com.example.backend.agent.service;

import java.util.List;

/**
 * 에이전트 초안을 업무 DB 에 저장한 결과. agent_runs.output_json 의 "saved" 로 남아 화면이 저장된 기록으로 이동할 수 있다.
 *
 * @param workDateAssumed AI 응답에 작업 날짜가 없어 오늘 날짜로 저장했으면 true (화면에서 날짜 확인 안내)
 * @param notes           저장하지 않았거나 바꾸지 않은 이유 (사람이 읽는 문장)
 */
public record SavedDrafts(Long workId, Long riskAssessmentId, List<SavedPermit> permits, Long tbmId,
                          boolean workDateAssumed, List<String> notes) {

    public record SavedPermit(Long permitId, String permitType, String permitNo, boolean created) {
    }

    public static SavedDrafts nothing() {
        return new SavedDrafts(null, null, List.of(), null, false, List.of());
    }

    public boolean isEmpty() {
        return workId == null;
    }
}
