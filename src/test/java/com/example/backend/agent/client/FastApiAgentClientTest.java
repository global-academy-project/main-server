package com.example.backend.agent.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 HTTP 로 가짜 AI 서버(JDK 내장 서버)를 불러 요청 모양·응답 읽기·오류 변환을 확인 */
@DisplayName("FastApiAgentClient: AI 서버 호출")
class FastApiAgentClientTest {

    private static final String TOKEN = "t".repeat(32);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private FastApiAgentClient start(int status, String body, AtomicReference<String> seenBody,
                                     AtomicReference<String> seenToken) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(FastApiAgentClient.WORK_REGISTER_PATH, ex -> {
            seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            seenToken.set(ex.getRequestHeaders().getFirst("X-Internal-Token"));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return new FastApiAgentClient("http://127.0.0.1:" + server.getAddress().getPort(), 2, 5, TOKEN, jsonMapper);
    }

    @Test
    @DisplayName("성공: 요청에 runId·siteId·slots·mode, 토큰 헤더. 응답 data 를 그대로 돌려줌")
    void success() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        FastApiAgentClient client = start(200,
                "{\"success\":true,\"data\":{\"runStatus\":\"WAITING_INPUT\",\"slots\":{\"askedKeys\":[\"location\"]}}}",
                body, token);

        AgentReply reply = client.workRegister(new WorkRegisterCall(345L, 5L, null, "용접 작업",
                jsonMapper.readTree("{\"askedKeys\":[\"x\"],\"future\":1}"), "VERIFY"));

        assertThat(jsonMapper.writeValueAsString(reply.data())).contains("\"runStatus\":\"WAITING_INPUT\"");
        assertThat(jsonMapper.writeValueAsString(reply.data().get("slots"))).contains("location");
        assertThat(token.get()).isEqualTo(TOKEN);
        assertThat(body.get()).contains("\"runId\":345", "\"siteId\":5", "\"permitId\":null", "\"mode\":\"VERIFY\"",
                "\"future\":1");   // slots 안의 모르는 필드도 그대로 전달
    }

    @Test
    @DisplayName("실패 응답: error.code 와 그때까지의 steps 를 AgentCallException 으로")
    void errorWithSteps() throws IOException {
        FastApiAgentClient client = start(503,
                "{\"success\":false,\"error\":{\"code\":\"LLM_UNAVAILABLE\",\"message\":\"모델 응답 없음\",\"details\":[]},"
                        + "\"steps\":[{\"seq\":1,\"kind\":\"TOOL\",\"name\":\"classify_work\",\"toolName\":\"classify_work\",\"status\":\"OK\"}]}",
                new AtomicReference<>(), new AtomicReference<>());

        assertThatThrownBy(() -> client.workRegister(new WorkRegisterCall(1L, 1L, null, "m", null, "VERIFY")))
                .isInstanceOfSatisfying(AgentCallException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("LLM_UNAVAILABLE");
                    assertThat(e.getMessage()).isEqualTo("모델 응답 없음");
                    assertThat(e.getSteps().isArray()).isTrue();
                });
    }

    @Test
    @DisplayName("응답이 JSON 이 아니면 AI_BAD_RESPONSE")
    void badResponse() throws IOException {
        FastApiAgentClient client = start(502, "<html>bad gateway</html>", new AtomicReference<>(), new AtomicReference<>());

        assertThatThrownBy(() -> client.workRegister(new WorkRegisterCall(1L, 1L, null, "m", null, "VERIFY")))
                .isInstanceOfSatisfying(AgentCallException.class, e -> assertThat(e.getCode()).isEqualTo("AI_BAD_RESPONSE"));
    }

    @Test
    @DisplayName("연결 실패면 AI_UNAVAILABLE")
    void unavailable() throws IOException {
        int port;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {   // 비어 있는 포트만 받고 바로 닫음
            port = s.getLocalPort();
        }
        FastApiAgentClient client = new FastApiAgentClient("http://127.0.0.1:" + port, 1, 2, TOKEN, jsonMapper);

        assertThatThrownBy(() -> client.workRegister(new WorkRegisterCall(1L, 1L, null, "m", null, "VERIFY")))
                .isInstanceOfSatisfying(AgentCallException.class, e -> assertThat(e.getCode()).isEqualTo("AI_UNAVAILABLE"));
    }
}
