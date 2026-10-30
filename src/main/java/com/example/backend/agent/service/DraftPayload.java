package com.example.backend.agent.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * AI 서버 작업 등록 DONE 응답 중 업무 DB 초안 저장에 쓰는 부분 (ai-model app/schemas/forms.py, agent_work.py).
 * 모르는 필드는 무시하고, 원본 JSON 은 각 기록의 form_data 와 agent_runs.output_json 에 그대로 남는다.
 * 각 부분은 없을 수 있다 (있는 것만 저장).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DraftPayload(Slots slots, Classification classification, Drafts drafts) {

    /** slots 중 저장에 쓰는 값. workDate 는 YYYY-MM-DD (AI 서버가 "내일" 등을 풀어 넣는다) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Slots(String workDate, List<String> utterances, String location, Integer headcount,
                        String startTime, String endTime) {
    }

    /** workTypes: "화기", "밀폐공간", "고소", "일반" 등 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Classification(List<String> workTypes) {
    }

    /** inspectionChecklist 는 저장하지 않는다 (구역·장비 id 가 없어서. 2주차 점검표 API 에서) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Drafts(RiskAssessment riskAssessment, List<Permit> permits, Object inspectionChecklist, Tbm tbm) {
    }

    /** HH:MM */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Period(String start, String end) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RiskAssessment(String title, String workSummary, String location, Period period, Integer headcount,
                                 List<RiskItem> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RiskItem(Integer seq, String hazard, List<String> accidentTypes, Integer frequency, Integer severity,
                           List<String> measures, Integer residualFrequency, Integer residualSeverity) {
    }

    /** formType: HOT_WORK_PERMIT / CONFINED_SPACE_PERMIT / HEIGHT_WORK_PERMIT */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Permit(String formType, String title, String location, Period period, Integer headcount,
                         List<Assignment> assignments) {
    }

    /** role: FIRE_WATCHER / ATTENDANT / SUPERVISOR */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Assignment(String role, String assignee) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tbm(String title, List<TbmPoint> points) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TbmPoint(Integer seq, String point) {
    }
}
