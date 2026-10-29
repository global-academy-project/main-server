package com.example.backend.agent.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 가짜 AI 서버 (app.agent.client=mock, 기본값). AI 서버 없이 화면·저장 흐름을 확인할 때 쓴다.
 *  - 첫 메시지(slots 없음): WAITING_INPUT + 되묻기
 *  - 다음 메시지: DONE + 단계·규칙 근거·승인 차단 사유 (초안 drafts 는 비움)
 * 응답 모양은 ai-model WorkRegisterResult 와 같다.
 */
@Component
@ConditionalOnProperty(name = "app.agent.client", havingValue = "mock", matchIfMissing = true)
public class MockAgentClient implements AgentClient {

    private static final String ASK = """
            {"runStatus": "WAITING_INPUT",
             "slots": {"utterances": [], "askedKeys": ["location", "measurements"]},
             "ask": {"message": "[mock] 작업 장소와 가스 측정값을 알려 주세요.",
                     "missing_fields": [{"key": "location", "label": "작업 장소", "question": "어디에서 작업하나요?"}]},
             "classification": {"workTypes": ["HOT"], "highRisk": true, "requiredPermits": ["HOT_WORK_PERMIT"],
                                "canStartBeforeApproval": false, "reasons": [], "excluded": [], "outOfScope": []},
             "citations": [], "verification": [], "guidance": [], "llmSources": {},
             "steps": [{"seq": 1, "kind": "TOOL", "name": "classify_work", "toolName": "classify_work", "status": "OK",
                        "input": {}, "output": {"workTypes": ["HOT"]}, "elapsedMs": 3}]}
            """;

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

    private final JsonMapper jsonMapper;

    public MockAgentClient(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public AgentReply workRegister(WorkRegisterCall call) {
        boolean first = call.slots() == null || call.slots().isNull();
        JsonNode data = jsonMapper.readTree(first ? ASK : DONE);
        return new AgentReply(data);
    }
}
