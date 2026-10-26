package com.example.backend.ocr.client;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.common.type.Engine;
import com.example.backend.common.type.NetworkMode;
import com.example.backend.common.type.PageSource;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** 실제 FastAPI OCR 서버를 호출하는 구현. app.ocr.client=fastapi 일 때만 사용한다. */
@Component
@ConditionalOnProperty(name = "app.ocr.client", havingValue = "fastapi")
public class FastApiOcrClient implements OcrClient {

    private static final Logger log = LoggerFactory.getLogger(FastApiOcrClient.class);
    private static final long ENGINE_CACHE_MS = 30_000;

    private final RestClient restClient;
    private volatile EngineInfo cachedEngines;
    private volatile long cachedAt;

    public FastApiOcrClient(@Value("${app.ocr.base-url:http://localhost:60100}") String baseUrl,
                            @Value("${app.ocr.timeout-seconds:600}") long timeoutSeconds) {
        // uvicorn 은 HTTP/1.1 만 쓰므로 JDK 클라이언트의 h2c 업그레이드를 막는다.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public EngineInfo getEngines() {
        EngineInfo cached = cachedEngines;
        if (cached != null && System.currentTimeMillis() - cachedAt < ENGINE_CACHE_MS) {
            return cached;
        }
        EnginesData data = execute(restClient.get().uri("/api/v1/ocr/engines"),
                new ParameterizedTypeReference<Envelope<EnginesData>>() {});

        Engine defaultEngine = parseEngine(data.defaultEngine());
        List<EngineItem> items = new ArrayList<>();
        boolean hasAuto = false;
        boolean defaultExternal = false;
        if (data.engines() != null) {
            for (EngineDto e : data.engines()) {
                Engine engine = parseEngine(e.name());
                if (engine == null) {
                    log.warn("알 수 없는 엔진은 목록에서 제외합니다: {}", e.name());
                    continue;
                }
                if (engine == Engine.AUTO) {
                    hasAuto = true;
                }
                if (engine == defaultEngine) {
                    defaultExternal = e.external();
                }
                items.add(new EngineItem(engine, e.available(), e.external(), e.reason()));
            }
        }
        if (!hasAuto) {
            items.add(0, new EngineItem(Engine.AUTO, true, defaultExternal, null));
        }
        EngineInfo info = new EngineInfo(parseNetworkMode(data.networkMode()),
                defaultEngine != null ? defaultEngine : Engine.AUTO, items);
        cachedEngines = info;
        cachedAt = System.currentTimeMillis();
        return info;
    }

    @Override
    public OcrResult extract(Path pdf, Engine engine, boolean extractImages) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.APPLICATION_PDF);
        form.add("file", new HttpEntity<>(new FileSystemResource(pdf), fileHeaders));
        form.add("engine", engine.name());
        form.add("extract_images", String.valueOf(extractImages));

        ExtractData d = execute(
                restClient.post().uri("/api/v1/ocr/extract")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(form),
                new ParameterizedTypeReference<Envelope<ExtractData>>() {});

        Engine actual = parseEngine(d.engine());
        if (actual == null) {
            log.warn("알 수 없는 엔진 응답({}), 요청한 엔진({})으로 기록합니다.", d.engine(), engine);
            actual = engine;
        }

        List<PageResult> pages = new ArrayList<>();
        if (d.pages() != null) {
            for (PageDto p : d.pages()) {
                pages.add(new PageResult(p.pageNo(), parseSource(p.source()), p.text() == null ? "" : p.text(),
                        p.confidence(), p.processingMs() == null ? 0 : p.processingMs()));
            }
        }
        List<ImageResult> images = new ArrayList<>();
        if (d.images() != null) {
            for (ImageDto i : d.images()) {
                byte[] bytes;
                try {
                    bytes = Base64.getDecoder().decode(i.dataBase64());
                } catch (RuntimeException e) {
                    throw new BusinessException(ErrorCode.OCR_SERVER_ERROR,
                            "OCR 서버가 보낸 이미지 데이터가 올바르지 않습니다. (page " + i.pageNo() + ")");
                }
                images.add(new ImageResult(i.pageNo(), i.format(), i.width(), i.height(), bytes));
            }
        }
        int pageCount = d.pageCount() != null ? d.pageCount() : pages.size();
        int totalMs = d.totalMs() != null ? d.totalMs().intValue() : 0;
        String summary = (d.summary() == null || d.summary().isBlank()) ? null : d.summary();
        return new OcrResult(actual, parseNetworkMode(d.networkMode()), pageCount, totalMs,
                d.avgConfidence(), pages, images, summary);
    }

    /** 호출하고 봉투를 벗겨 data 를 돌려준다. 실패는 BusinessException 으로 바꾼다. */
    private <T> T execute(RestClient.RequestHeadersSpec<?> spec,
                          ParameterizedTypeReference<Envelope<T>> type) {
        try {
            Envelope<T> env = spec.exchange((request, response) -> {
                if (response.getStatusCode().isError()) {
                    ErrorEnvelope err;
                    try {
                        err = response.bodyTo(ErrorEnvelope.class);
                    } catch (RuntimeException e) {
                        err = null;
                    }
                    throw toException(response.getStatusCode().value(), err);
                }
                return response.bodyTo(type);
            });
            if (env == null || !env.success() || env.data() == null) {
                throw new BusinessException(ErrorCode.OCR_SERVER_ERROR, "OCR 서버 응답이 올바르지 않습니다.");
            }
            return env.data();
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {
            log.warn("OCR 서버 호출 실패: {}", e.getMessage());
            throw new BusinessException(ErrorCode.OCR_SERVER_ERROR,
                    "OCR 서버에 연결할 수 없거나 응답 시간이 초과되었습니다.");
        }
    }

    private BusinessException toException(int status, ErrorEnvelope env) {
        String code = null;
        String message = null;
        if (env != null && env.error() != null) {
            code = env.error().code();
            message = env.error().message();
        }
        if (message == null || message.isBlank()) {
            message = "OCR 서버 오류 (HTTP " + status + ")";
        }
        return new BusinessException(mapCode(code), message);
    }

    private ErrorCode mapCode(String code) {
        if (code == null) {
            return ErrorCode.OCR_SERVER_ERROR;
        }
        return switch (code) {
            case "FILE_TOO_LARGE" -> ErrorCode.FILE_TOO_LARGE;
            case "UNSUPPORTED_FILE_TYPE" -> ErrorCode.UNSUPPORTED_FILE_TYPE;
            case "ENGINE_NOT_ALLOWED" -> ErrorCode.ENGINE_NOT_ALLOWED;
            case "INVALID_REQUEST", "PAGE_LIMIT_EXCEEDED" -> ErrorCode.INVALID_REQUEST;
            default -> ErrorCode.OCR_SERVER_ERROR;
        };
    }

    private Engine parseEngine(String name) {
        if (name == null) {
            return null;
        }
        try {
            return Engine.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private NetworkMode parseNetworkMode(String name) {
        try {
            return NetworkMode.valueOf(name.trim().toUpperCase());
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.OCR_SERVER_ERROR, "알 수 없는 망 모드: " + name);
        }
    }

    private PageSource parseSource(String name) {
        try {
            return PageSource.valueOf(name.trim().toUpperCase());
        } catch (RuntimeException e) {
            return PageSource.OCR;
        }
    }

    // ---- FastAPI 응답 모양 (필요한 필드만, 모르는 필드는 무시) ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope<T>(boolean success, T data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorEnvelope(ErrorBody error) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorBody(String code, String message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnginesData(String networkMode, String defaultEngine, List<EngineDto> engines) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EngineDto(String name, boolean available, boolean external, String reason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExtractData(String engine, String networkMode, Integer pageCount, Number totalMs,
                       BigDecimal avgConfidence, List<PageDto> pages, List<ImageDto> images,
                       String summary) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PageDto(@JsonAlias("pageNum") int pageNo, String source, String text,
                   BigDecimal confidence, Integer processingMs) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ImageDto(@JsonAlias("pageNum") int pageNo, String format, int width, int height,
                    String dataBase64) {}
}