package com.example.backend.agent.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 가짜 AI 서버 (app.agent.client=mock, 기본값). AI 서버 없이 화면·저장 흐름을 확인할 때 쓴다.
 *  - 첫 메시지(slots 없음)에 작업 단서가 없으면: 시나리오를 못 정함 (WAITING_INPUT, slots 는 pendingText 만,
 *    missing_fields 빈 목록, classification 비어 있음, steps·drafts 없음). B 11/04 slots 안내 2장
 *  - 첫 메시지에 작업 단서가 있거나, 직전 slots 에 pendingText 가 있으면: 작업 등록 되묻기 (WAITING_INPUT,
 *    선택 항목 LEL 을 끝에 한 번 물음 → slots.askedOptionalKeys)
 *  - 그다음 메시지: 답이 없어도 DONE + 단계·규칙 근거·승인 차단 사유 (초안 drafts 는 비움)
 * 응답 모양은 ai-model WorkRegisterResult 와 같다.
 * 기록 질문(recordQuery)은 실제 기록을 조회하지 않고 질문을 그대로 되돌려 주는 DONE 답변만 돌려준다
 * (화면·저장 흐름 확인용. 실제 조회는 AI 서버가 search_records 를 불러야 한다).
 */
@Component
@ConditionalOnProperty(name = "app.agent.client", havingValue = "mock", matchIfMissing = true)
public class MockAgentClient implements AgentClient {

    private static final String ASK = """
            {"runStatus": "WAITING_INPUT",
             "slots": {"utterances": [], "askedKeys": ["location"], "askedOptionalKeys": ["lel"]},
             "ask": {"message": "[mock] 작업 장소를 알려 주세요. 가연성 가스(LEL)를 측정했다면 값도 알려 주세요.",
                     "missing_fields": [{"key": "location", "label": "작업 장소", "question": "어디에서 작업하나요?"}]},
             "classification": {"workTypes": ["HOT"], "highRisk": true, "requiredPermits": ["HOT_WORK_PERMIT"],
                                "canStartBeforeApproval": false, "reasons": [], "excluded": [], "outOfScope": []},
             "citations": [], "verification": [], "guidance": [], "llmSources": {},
             "steps": [{"seq": 1, "kind": "TOOL", "name": "classify_work", "toolName": "classify_work", "status": "OK",
                        "input": {}, "output": {"workTypes": ["HOT"]}, "elapsedMs": 3}]}
            """;

    private static final String UNKNOWN = """
            {"runStatus": "WAITING_INPUT",
             "slots": {"pendingText": ""},
             "ask": {"message": "[mock] 어떤 일을 하시려는지 조금 더 알려 주세요. 예: 내일 R-201 배관 용접",
                     "missing_fields": []},
             "classification": {"workTypes": [], "highRisk": null, "requiredPermits": [],
                                "canStartBeforeApproval": null, "reasons": [], "excluded": [], "outOfScope": []},
             "citations": [], "verification": [], "guidance": [], "llmSources": {}}
            """;

    /** 첫 메시지가 작업 이야기인지 보는 단서 (가짜 분류). 없으면 UNKNOWN 흐름 */
    private static final java.util.List<String> WORK_WORDS = java.util.List.of(
            "작업", "용접", "용단", "절단", "그라인더", "화기", "밀폐", "탱크", "맨홀", "고소", "비계", "사다리",
            "점검", "설치", "교체", "수리", "배관", "도장");

    private static final String DONE = """
            {"runStatus": "DONE",
             "slots": {"utterances": [], "askedKeys": []},
             "classification": {"workTypes": ["HOT"], "highRisk": true, "requiredPermits": ["HOT_WORK_PERMIT"],
                                "canStartBeforeApproval": false, "reasons": [], "excluded": [], "outOfScope": []},
             "ruleCheck": {"blocked": true, "evaluations": [], "conflicts": [], "notes": []},
             "drafts": null,
             "citations": [{"kind": "RULE", "refId": "HOT_LEL_MAX", "source": "[mock] 화기작업 가연성 가스 기준",
                            "snippet": "가연성 가스 농도 10 %LEL 미만"}],
             "approvalGate": {"canRequest": false, "reasons": ["[mock] 가스 측정값이 없어 승인 요청을 할 수 없습니다."]},
             "verification": [], "guidance": [], "llmSources": {"plan": "fallback", "judgment": "fallback"},
             "steps": [
               {"seq": 1, "kind": "TOOL", "name": "classify_work", "toolName": "classify_work", "status": "OK",
                "input": {}, "output": {}, "elapsedMs": 3},
               {"seq": 2, "kind": "PHASE", "name": "evidence.plan", "toolName": null, "status": "OK",
                "input": {}, "output": {}, "elapsedMs": 5},
               {"seq": 3, "kind": "PHASE", "name": "evidence.search", "toolName": null, "status": "OK",
                "input": {}, "output": {}, "elapsedMs": 9},
               {"seq": 4, "kind": "TOOL", "name": "check_rules", "toolName": "check_rules", "status": "OK",
                "input": {"permit_types": ["HOT_WORK_PERMIT"]}, "output": {"blocked": true}, "elapsedMs": 40},
               {"seq": 5, "kind": "PHASE", "name": "judgment", "toolName": null, "status": "OK",
                "input": {}, "output": {}, "elapsedMs": 7}]}
            """;

    private static final String RECORD_DONE = """
            {"runStatus": "DONE",
             "answer": "",
             "queries": [{"queryType": "missing_measurements",
                          "conditions": {"from": "2026-10-01", "to": "2026-10-31", "permitType": "HOT"},
                          "total": 0, "truncated": false}],
             "evidence": [],
             "context": {"turns": 1},
             "steps": [
               {"seq": 1, "kind": "PHASE", "name": "query.plan", "toolName": null, "status": "OK",
                "input": {}, "output": {"queryType": "missing_measurements"}, "elapsedMs": 4},
               {"seq": 2, "kind": "TOOL", "name": "search_records", "toolName": "search_records", "status": "OK",
                "input": {"queryType": "missing_measurements"}, "output": {"total": 0}, "elapsedMs": 12},
               {"seq": 3, "kind": "PHASE", "name": "verification", "toolName": null, "status": "OK",
                "input": {}, "output": {}, "elapsedMs": 2}]}
            """;

    private final JsonMapper jsonMapper;

    public MockAgentClient(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public AgentReply workRegister(WorkRegisterCall call) {
        JsonNode slots = call.slots();
        boolean first = slots == null || slots.isNull();
        if (first && !looksLikeWork(call.message())) {
            tools.jackson.databind.node.ObjectNode data = (tools.jackson.databind.node.ObjectNode) jsonMapper.readTree(UNKNOWN);
            ((tools.jackson.databind.node.ObjectNode) data.get("slots")).put("pendingText", call.message());
            return new AgentReply(data);
        }
        // AI 서버와 같은 규칙: slots.pendingText 가 비어 있지 않으면 시나리오 되묻기의 답 (다른 slots 필드는 빈 값으로 함께 옴)
        JsonNode pendingText = first ? null : slots.path("pendingText");
        boolean pending = pendingText != null && pendingText.isValueNode() && !pendingText.isNull()
                && !pendingText.asString().isBlank();
        JsonNode data = jsonMapper.readTree(first || pending ? ASK : DONE);
        return new AgentReply(data);
    }

    static boolean looksLikeWork(String message) {
        return message != null && WORK_WORDS.stream().anyMatch(message::contains);
    }

    @Override
    public AgentReply recordQuery(RecordQueryCall call) {
        tools.jackson.databind.node.ObjectNode data = (tools.jackson.databind.node.ObjectNode) jsonMapper.readTree(RECORD_DONE);
        data.put("answer", "[mock] \"" + call.message() + "\" 질문을 받았습니다. 실제 기록 조회는 AI 서버(기록 질문 에이전트) 연결 후 됩니다.");
        return new AgentReply(data);
    }
}
