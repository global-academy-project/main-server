package com.example.backend.nearmiss.kb;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * kb-ingest 호출. Authorization: Bearer KB_INGEST_API_TOKEN.
 *   POST {app.kb-ingest.base-url}/ingest/chunks         {collection, chunks[{point_id, text, payload}]} → {"indexed": n}
 *   POST {app.kb-ingest.base-url}/ingest/points/delete  {collection, point_ids[]} (1~32개) → {"deleted": n}
 * 토큰·본문은 로그에 남기지 않는다. 2xx 가 아니면 상태 코드만 담아 예외.
 */
@Component
@ConditionalOnProperty(name = "app.kb-ingest.client", havingValue = "http")
public class HttpKbIngestClient implements KbIngestClient {

    static final String PATH = "/ingest/chunks";
    static final String DELETE_PATH = "/ingest/points/delete";

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final String token;

    public HttpKbIngestClient(@Value("${app.kb-ingest.base-url}") String baseUrl,
                              @Value("${app.kb-ingest.timeout-seconds}") long timeoutSeconds,
                              @Value("${app.kb-ingest.token}") String token, JsonMapper jsonMapper) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("app.kb-ingest.client=http 이면 KB_INGEST_API_TOKEN 이 필요합니다.");
        }
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.jsonMapper = jsonMapper;
        this.token = token.trim();
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public int upsert(String collection, List<Chunk> chunks) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", collection);
        body.put("chunks", chunks.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("point_id", c.pointId());
            m.put("text", c.text());
            m.put("payload", c.payload());
            return m;
        }).toList());
        return post(PATH, body, "indexed");
    }

    @Override
    public int delete(String collection, List<String> pointIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", collection);
        body.put("point_ids", pointIds);
        return post(DELETE_PATH, body, "deleted");
    }

    private int post(String path, Map<String, Object> body, String countField) {
        String raw = restClient.post().uri(path).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token)
                .body(jsonMapper.writeValueAsString(body))
                .exchange((req, res) -> {
                    if (!res.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException("kb-ingest 응답 " + res.getStatusCode().value());
                    }
                    return new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                });
        JsonNode count = jsonMapper.readTree(raw).get(countField);
        if (count == null || !count.isNumber()) {
            throw new IllegalStateException("kb-ingest 응답 모양이 다릅니다.");
        }
        return count.asInt();
    }
}
