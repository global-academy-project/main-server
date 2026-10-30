package com.example.backend.agent.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 실제 AI 서버(main-ai-model) 호출. app.agent.client=fastapi 일 때만.
 *   POST {base-url}/api/v1/agent/work-register
 *   POST {base-url}/api/v1/agent/record-query
 *   성공 {"success": true, "data": WorkRegisterResult}
 *   실패 {"success": false, "error": {"code", "message", "details"}, "steps": [...]}
 * 연결·응답 시간 제한은 application.yaml (app.agent.*). 토큰은 /internal 과 같은 값을 X-Internal-Token 으로 보낸다.
 */
@Component
@ConditionalOnProperty(name = "app.agent.client", havingValue = "fastapi")
public class FastApiAgentClient implements AgentClient {

    static final String WORK_REGISTER_PATH = "/api/v1/agent/work-register";
    static final String RECORD_QUERY_PATH = "/api/v1/agent/record-query";
    private static final Logger log = LoggerFactory.getLogger(FastApiAgentClient.class);

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final String token;

    public FastApiAgentClient(@Value("${app.agent.base-url}") String baseUrl,
                              @Value("${app.agent.connect-timeout-seconds}") long connectTimeoutSeconds,
                              @Value("${app.agent.read-timeout-seconds}") long readTimeoutSeconds,
                              @Value("${app.internal.token}") String token,
                              JsonMapper jsonMapper) {
        // uvicorn 은 HTTP/1.1 만 쓰므로 h2c 업그레이드를 막는다 (OCR 클라이언트와 같음)
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.jsonMapper = jsonMapper;
        this.token = token;
    }

    @Override
    public AgentReply workRegister(WorkRegisterCall call) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", call.runId());
        body.put("siteId", call.siteId());
        body.put("permitId", call.permitId());
        body.put("message", call.message());
        body.put("slots", call.slots());
        body.put("mode", call.mode());
        return post(WORK_REGISTER_PATH, body);
    }

    @Override
    public AgentReply recordQuery(RecordQueryCall call) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", call.runId());
        body.put("siteId", call.siteId());
        body.put("message", call.message());
        body.put("context", call.context());
        body.put("mode", call.mode());
        return post(RECORD_QUERY_PATH, body);
    }

    private AgentReply post(String path, Map<String, Object> body) {
        Raw raw;
        try {
            raw = restClient.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("X-Internal-Token", token)
                    .body(jsonMapper.writeValueAsString(body))
                    .exchange((request, response) -> new Raw(response.getStatusCode().value(),
                            new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (RestClientException e) {
            log.warn("AI 서버 호출 실패: path={} reason={}", path, e.getMessage());
            throw new AgentCallException("AI_UNAVAILABLE", "AI 서버에 연결할 수 없거나 응답 시간이 초과되었습니다.", null);
        }
        return read(raw);
    }

    AgentReply read(Raw raw) {
        Envelope env;
        try {
            env = jsonMapper.readValue(raw.body(), Envelope.class);
        } catch (JacksonException e) {
            env = null;
        }
        if (env == null) {
            log.warn("AI 서버 응답 모양이 다름: status={}", raw.status());
            throw new AgentCallException("AI_BAD_RESPONSE", "AI 서버 응답을 읽을 수 없습니다. (HTTP " + raw.status() + ")", null);
        }
        if (raw.status() >= 200 && raw.status() < 300 && Boolean.TRUE.equals(env.success()) && env.data() != null
                && env.data().isObject()) {
            return new AgentReply(env.data());
        }
        String code = env.error() != null && env.error().code() != null ? env.error().code() : "AI_ERROR";
        String message = env.error() != null && env.error().message() != null && !env.error().message().isBlank()
                ? env.error().message() : "AI 서버 오류 (HTTP " + raw.status() + ")";
        throw new AgentCallException(code, message, env.steps() != null && env.steps().isArray() ? env.steps() : null);
    }

    record Raw(int status, String body) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope(Boolean success, JsonNode data, Error error, JsonNode steps) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Error(String code, String message) {
    }
}
