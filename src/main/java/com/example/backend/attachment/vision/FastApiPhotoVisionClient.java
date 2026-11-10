package com.example.backend.attachment.vision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * ai-model 사진 인식 호출: POST {app.agent.base-url}/api/v1/vision/tbm-photo (multipart file, 헤더 X-Internal-Token).
 * 응답 {success: true, data: PhotoVision}. 실패·시간 초과면 비어 있는 값 (파일 정보 확인 결과만 남음).
 */
@Component
@ConditionalOnProperty(name = "app.photo.vision.client", havingValue = "fastapi")
public class FastApiPhotoVisionClient implements PhotoVisionClient {

    static final String PATH = "/api/v1/vision/tbm-photo";
    private static final Logger log = LoggerFactory.getLogger(FastApiPhotoVisionClient.class);

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final String token;

    public FastApiPhotoVisionClient(@Value("${app.agent.base-url}") String baseUrl,
                                    @Value("${app.photo.vision.timeout-seconds}") long timeoutSeconds,
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
    public Optional<PhotoVision> detect(byte[] image, String mimeType, String fileName) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(image) {
            @Override
            public String getFilename() {
                return fileName == null ? "photo" : fileName;
            }
        });
        try {
            String raw = restClient.post().uri(PATH).contentType(MediaType.MULTIPART_FORM_DATA)
                    .accept(MediaType.APPLICATION_JSON).header("X-Internal-Token", token).body(form)
                    .exchange((req, res) -> res.getStatusCode().is2xxSuccessful()
                            ? new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8) : null);
            if (raw == null) {
                log.warn("사진 인식 실패: HTTP 오류");
                return Optional.empty();
            }
            JsonNode env = jsonMapper.readTree(raw);
            if (!env.path("success").asBoolean(false) || !env.path("data").isObject()) {
                log.warn("사진 인식 응답 모양이 다름");
                return Optional.empty();
            }
            return Optional.of(jsonMapper.treeToValue(env.path("data"), PhotoVision.class));
        } catch (RestClientException | JacksonException | IllegalArgumentException e) {
            log.warn("사진 인식 실패: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public boolean enabled() {
        return true;
    }
}
