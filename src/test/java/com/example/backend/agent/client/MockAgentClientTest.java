package com.example.backend.agent.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 가짜 AI 서버 흐름: 시나리오 못 정함(pendingText) → 되묻기(LEL 한 번) → 완료 (B 11/04 slots 안내) */
@DisplayName("가짜 AI 서버 작업 등록 흐름")
class MockAgentClientTest {

    private final MockAgentClient client = new MockAgentClient(JsonMapper.builder().build());

    private JsonNode call(String message, JsonNode slots) {
        return client.workRegister(new WorkRegisterCall(1L, 1L, null, message, slots, "VERIFY")).data();
    }

    @Test
    @DisplayName("작업 단서가 없으면 pendingText 만 담은 WAITING_INPUT, 다음 말에서 되묻기, 그다음 DONE")
    void unknownThenAskThenDone() {
        JsonNode unknown = call("안녕하세요", null);
        assertThat(unknown.path("runStatus").asString()).isEqualTo("WAITING_INPUT");
        assertThat(unknown.path("slots").path("pendingText").asString()).isEqualTo("안녕하세요");
        assertThat(unknown.path("ask").path("missing_fields").size()).isZero();
        assertThat(unknown.path("classification").path("highRisk").isNull()).isTrue();
        assertThat(unknown.has("steps")).isFalse();
        assertThat(unknown.has("drafts")).isFalse();

        JsonNode ask = call("내일 R-201 배관 용접이요", unknown.path("slots"));
        assertThat(ask.path("runStatus").asString()).isEqualTo("WAITING_INPUT");
        assertThat(ask.path("slots").path("askedOptionalKeys").get(0).asString()).isEqualTo("lel");

        JsonNode done = call("모르겠어요", ask.path("slots"));
        assertThat(done.path("runStatus").asString()).isEqualTo("DONE");
        assertThat(done.path("approvalGate").path("canRequest").asBoolean(true)).isFalse();
    }

    @Test
    @DisplayName("첫 말에 작업 단서가 있으면 바로 되묻기")
    void workFirst() {
        JsonNode ask = call("탱크 안에서 용접합니다", null);
        assertThat(ask.path("slots").has("pendingText")).isFalse();
        assertThat(ask.path("slots").path("askedKeys").size()).isPositive();
    }
}
