package com.example.backend.extract.service;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ai-model LLM 추출 호출: POST {app.agent.base-url}/api/v1/extract/rule-input (헤더 X-Internal-Token).
 * 요청 {text, measurementItems, workTypes} → 응답 {"success": true, "data": LlmExtraction}.
 * 연결 실패·시간 초과·응답 모양 이상이면 빈 값 (규칙 추출만으로 계속). 원문·토큰은 로그에 남기지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.extract.llm.client", havingValue = "fastapi")
public class FastApiLlmExtractClient implements LlmExtractClient {

    static final String PATH = "/api/v1/extract/rule-input";
    private static final Logger log = LoggerFactory.getLogger(FastApiLlmExtractClient.class);

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final String token;

    public FastApiLlmExtractClient(@Value("${app.agent.base-url}") String baseUrl,
                                   @Value("${app.extract.llm.timeout-seconds}") long timeoutSeconds,
                                   @Value("${app.internal.token}") String token, JsonMapper jsonMapper) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.jsonMapper = jsonMapper;
        this.token = token;
    }

    @Override
    public Optional<LlmExtraction> extract(String text, List<String> items, List<String> workTypes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        body.put("measurementItems", items);
        body.put("workTypes", workTypes);
        try {
            String raw = restClient.post().uri(PATH).contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON).header("X-Internal-Token", token)
                    .body(jsonMapper.writeValueAsString(body))
                    .exchange((req, res) -> res.getStatusCode().is2xxSuccessful()
                            ? new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8) : null);
            if (raw == null) {
                log.warn("LLM 추출 실패: HTTP 오류");
                return Optional.empty();
            }
            JsonNode env = jsonMapper.readTree(raw);
            if (!env.path("success").asBoolean(false) || !env.path("data").isObject()) {
                log.warn("LLM 추출 응답 모양이 다름");
                return Optional.empty();
            }
            return Optional.of(jsonMapper.treeToValue(env.path("data"), LlmExtraction.class));
        } catch (RestClientException | JacksonException | IllegalArgumentException e) {
            log.warn("LLM 추출 실패: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public String name() {
        return "LLM";
    }
}
