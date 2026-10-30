package com.example.backend.agent.service;

import com.example.backend.permit.entity.PermitType;
import com.example.backend.risk.entity.RiskLevel;
import com.example.backend.rule.entity.WorkType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 초안 → 저장 계획 변환 (DB 없이): 종류·등급·발생형태·시각 처리 */
@DisplayName("에이전트 초안 변환 규칙")
class AgentDraftWriterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AgentDraftWriter writer = new AgentDraftWriter(null, null, null, null, null, null, null, null, null,
            null, mapper, CLOCK, 12, 6);

    private AgentDraftWriter.Plan plan(String json) {
        JsonNode node = mapper.readTree(json);
        return writer.plan(mapper.treeToValue(node, DraftPayload.class), node.path("drafts"));
    }

    @Test
    @DisplayName("등급: 12 이상 HIGH, 6 이상 MEDIUM, 그 아래 LOW (yaml 기준)")
    void levels() {
        assertThat(writer.levelOf(20)).isEqualTo(RiskLevel.HIGH);
        assertThat(writer.levelOf(12)).isEqualTo(RiskLevel.HIGH);
        assertThat(writer.levelOf(11)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(writer.levelOf(6)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(writer.levelOf(5)).isEqualTo(RiskLevel.LOW);
        assertThatThrownBy(() -> new AgentDraftWriter(null, null, null, null, null, null, null, null, null, null,
                mapper, CLOCK, 6, 6)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("작업 종류: 첫 허가서 종류 → 없으면 분류 결과(한글) → 없으면 일반")
    void workTypes() {
        assertThat(AgentDraftWriter.workTypeOf(List.of(new DraftPayload.Permit("CONFINED_SPACE_PERMIT", null, null, null,
                null, null)), null)).isEqualTo(WorkType.CONFINED);
        assertThat(AgentDraftWriter.workTypeOf(List.of(), new DraftPayload.Classification(List.of("일반", "고소"))))
                .isEqualTo(WorkType.HEIGHT);
        assertThat(AgentDraftWriter.workTypeOf(null, null)).isEqualTo(WorkType.GENERAL);
        assertThat(AgentDraftWriter.permitTypeOf("HOT_WORK_PERMIT")).isEqualTo(PermitType.HOT);
        assertThatThrownBy(() -> AgentDraftWriter.permitTypeOf("CRANE_PERMIT"))
                .isInstanceOf(AgentDraftWriter.DraftInvalidException.class);
    }

    @Test
    @DisplayName("발생형태: 아는 첫 값만 코드로 (모르는 이름만 있으면 null), 대책은 줄바꿈으로")
    void accidentCodes() {
        AgentDraftWriter.Plan p = plan("""
                {"slots": {"workDate": "2026-11-02"},
                 "drafts": {"riskAssessment": {"workSummary": "배관 교체", "period": {"start": "9:00", "end": "11:30"},
                   "items": [{"seq": 1, "hazard": "착화", "accidentTypes": ["절단·베임", "폭발·파열"], "frequency": 2, "severity": 3,
                              "measures": ["측정", " ", "감시"], "residualFrequency": 1, "residualSeverity": 3},
                             {"seq": 2, "hazard": "베임", "accidentTypes": ["기타"], "frequency": 1, "severity": 1,
                              "measures": ["장갑"]}]}}}
                """);
        assertThat(p.risk()).extracting(AgentDraftWriter.RiskItemPlan::accidentType).containsExactly("EXPLOSION", null);
        assertThat(p.risk().get(0).controls()).isEqualTo("측정\n감시");
        assertThat(p.risk().get(0).level()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(p.startAt().getHour()).isEqualTo(9);   // "9:00" 한 자리 시도 읽음
        assertThat(p.workDate()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(p.workDateAssumed()).isFalse();
    }

    @Test
    @DisplayName("날짜 없음 → 오늘(Asia/Seoul) + 안내, 종료가 시작보다 이르면 종료만 비움, 점검표는 저장 안 함 안내")
    void datesAndNotes() {
        AgentDraftWriter.Plan p = plan("""
                {"slots": {"startTime": "22:00", "endTime": "02:00"},
                 "drafts": {"tbm": {"points": [{"seq": 1, "point": "가스측정 확인"}]},
                            "inspectionChecklist": {"items": []}}}
                """);
        assertThat(p.workDate()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(p.workDateAssumed()).isTrue();
        assertThat(p.startAt()).isNotNull();
        assertThat(p.endAt()).isNull();
        assertThat(p.notes()).anyMatch(n -> n.contains("날짜를 확인해 주세요"))
                .anyMatch(n -> n.contains("종료 시각"))
                .anyMatch(n -> n.contains("점검표"));
        assertThat(p.tbmPoints()).containsExactly("가스측정 확인");
    }

    @Test
    @DisplayName("값이 맞지 않으면 저장 전에 DraftInvalidException (위험요인 없음, 대책 없음, 개선 후 값 한쪽만)")
    void invalid() {
        assertThatThrownBy(() -> plan("""
                {"drafts": {"riskAssessment": {"workSummary": "x", "items": []}}}
                """)).isInstanceOf(AgentDraftWriter.DraftInvalidException.class);
        assertThatThrownBy(() -> plan("""
                {"drafts": {"riskAssessment": {"workSummary": "x", "items": [{"seq": 1, "hazard": "h", "frequency": 1,
                  "severity": 1, "measures": []}]}}}
                """)).isInstanceOf(AgentDraftWriter.DraftInvalidException.class);
        assertThatThrownBy(() -> plan("""
                {"drafts": {"riskAssessment": {"workSummary": "x", "items": [{"seq": 1, "hazard": "h", "frequency": 1,
                  "severity": 1, "measures": ["m"], "residualFrequency": 1}]}}}
                """)).isInstanceOf(AgentDraftWriter.DraftInvalidException.class);
    }
}
