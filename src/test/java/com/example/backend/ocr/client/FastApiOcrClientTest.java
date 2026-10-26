package com.example.backend.ocr.client;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.common.type.Engine;
import com.example.backend.common.type.NetworkMode;
import com.example.backend.common.type.PageSource;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.example.backend.support.TestFixtures.expectCode;
import static com.example.backend.support.TestFixtures.pdf;
import static org.assertj.core.api.Assertions.assertThat;

/** JDK 내장 HttpServer 로 FastAPI 를 흉내 내서, 실제 HTTP 요청/응답 변환과 오류 매핑을 검증한다. */
@DisplayName("FastApiOcrClient: 엔진 조회 / 추출 / 오류 매핑 / 타임아웃")
class FastApiOcrClientTest {

    private static final String ENGINES_PATH = "/api/v1/ocr/engines";
    private static final String EXTRACT_PATH = "/api/v1/ocr/extract";

    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private String baseUrl;
    private Path pdfFile;

    @BeforeEach
    void startServer(@TempDir Path dir) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        pdfFile = dir.resolve("a.pdf");
        Files.write(pdfFile, pdf(1));
    }

    @AfterEach
    void stopServer() {
        try {
            server.stop(0);   // 연결 거부 테스트에서 이미 멈춘 경우에도 안전하게
        } catch (RuntimeException ignored) {
            // no-op
        }
        executor.shutdownNow();
    }

    private FastApiOcrClient client() {
        return new FastApiOcrClient(baseUrl, 5);
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, ex -> send(ex, status, body));
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static String enginesJson(String networkMode, String defaultEngine, String enginesArray) {
        return "{\"success\":true,\"data\":{\"networkMode\":\"" + networkMode + "\",\"defaultEngine\":\""
                + defaultEngine + "\",\"engines\":" + enginesArray + "}}";
    }

    // ---------------- getEngines ----------------

    @Test
    @DisplayName("엔진 목록: 응답을 변환한다 (망 모드 대소문자 무시, 모르는 엔진은 제외)")
    void engines_parsed() {
        respond(ENGINES_PATH, 200, enginesJson("closed", "TESSERACT", """
                [{"name":"AUTO","available":true,"external":false},
                 {"name":"tesseract","available":true,"external":false},
                 {"name":"OPENAI","available":false,"external":true,"reason":"망 분리"},
                 {"name":"NEW_ENGINE","available":true,"external":false}]"""));

        EngineInfo info = client().getEngines();

        assertThat(info.networkMode()).isEqualTo(NetworkMode.CLOSED);
        assertThat(info.defaultEngine()).isEqualTo(Engine.TESSERACT);
        assertThat(info.engines()).extracting(EngineItem::name)
                .containsExactly(Engine.AUTO, Engine.TESSERACT, Engine.OPENAI);
        EngineItem openai = info.engines().get(2);
        assertThat(openai.available()).isFalse();
        assertThat(openai.reason()).isEqualTo("망 분리");
    }

    @Test
    @DisplayName("AUTO 가 응답에 없으면 맨 앞에 추가하고, external 은 기본 엔진을 따른다")
    void engines_autoInserted() {
        respond(ENGINES_PATH, 200, enginesJson("OPEN", "OPENAI", """
                [{"name":"TESSERACT","available":true,"external":false},
                 {"name":"OPENAI","available":true,"external":true}]"""));

        EngineInfo info = client().getEngines();

        assertThat(info.engines().get(0).name()).isEqualTo(Engine.AUTO);
        assertThat(info.engines().get(0).available()).isTrue();
        assertThat(info.engines().get(0).external()).isTrue();
    }

    @Test
    @DisplayName("기본 엔진이 알 수 없는 값이면 AUTO 로 대체")
    void engines_unknownDefault_fallsBackToAuto() {
        respond(ENGINES_PATH, 200, enginesJson("OPEN", "???", "[]"));

        assertThat(client().getEngines().defaultEngine()).isEqualTo(Engine.AUTO);
    }

    @Test
    @DisplayName("엔진 목록은 30초 동안 캐시되어 서버를 한 번만 호출한다")
    void engines_cached() {
        AtomicInteger hits = new AtomicInteger();
        server.createContext(ENGINES_PATH, ex -> {
            hits.incrementAndGet();
            send(ex, 200, enginesJson("OPEN", "AUTO", "[]"));
        });
        FastApiOcrClient c = client();

        c.getEngines();
        c.getEngines();
        c.getEngines();

        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("알 수 없는 망 모드는 OCR_SERVER_ERROR")
    void engines_invalidNetworkMode() {
        respond(ENGINES_PATH, 200, enginesJson("WIFI", "AUTO", "[]"));

        BusinessException e = expectCode(() -> client().getEngines(), ErrorCode.OCR_SERVER_ERROR);
        assertThat(e.getMessage()).contains("망 모드");
    }

    @Test
    @DisplayName("success=false 응답은 OCR_SERVER_ERROR")
    void engines_successFalse() {
        respond(ENGINES_PATH, 200, "{\"success\":false}");

        BusinessException e = expectCode(() -> client().getEngines(), ErrorCode.OCR_SERVER_ERROR);
        assertThat(e.getMessage()).contains("응답이 올바르지 않습니다");
    }

    @Test
    @DisplayName("HTTP 500 + 오류 본문이면 서버가 준 메시지를 사용")
    void engines_httpError_usesServerMessage() {
        respond(ENGINES_PATH, 500, "{\"success\":false,\"error\":{\"code\":\"INTERNAL\",\"message\":\"boom\"}}");

        BusinessException e = expectCode(() -> client().getEngines(), ErrorCode.OCR_SERVER_ERROR);
        assertThat(e.getMessage()).isEqualTo("boom");
    }

    // ---------------- extract ----------------

    private static String extractJson(String engine, String pagesAndRest) {
        return "{\"success\":true,\"data\":{\"engine\":\"" + engine + "\",\"networkMode\":\"OPEN\"," + pagesAndRest + "}}";
    }

    @Test
    @DisplayName("추출 성공: multipart 로 file/engine/extract_images 를 보내고 응답을 변환한다")
    void extract_success() {
        AtomicReference<String> requestBody = new AtomicReference<>();
        String png = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        server.createContext(EXTRACT_PATH, ex -> {
            requestBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            send(ex, 200, extractJson("TESSERACT", """
                    "pageCount":2,"totalMs":1234.7,"avgConfidence":0.9,"summary":"요약",
                    "pages":[{"pageNo":1,"source":"OCR","text":"안녕","confidence":0.9,"processingMs":50},
                             {"pageNum":2,"source":"TEXT_LAYER","text":null,"confidence":null}],
                    "images":[{"pageNum":1,"format":"png","width":10,"height":20,"dataBase64":"%s"}]""".formatted(png)));
        });

        OcrResult r = client().extract(pdfFile, Engine.TESSERACT, true);

        assertThat(requestBody.get()).contains("name=\"file\"").contains("name=\"engine\"")
                .contains("TESSERACT").contains("name=\"extract_images\"").contains("true");
        assertThat(r.engine()).isEqualTo(Engine.TESSERACT);
        assertThat(r.pageCount()).isEqualTo(2);
        assertThat(r.totalMs()).isEqualTo(1234);                       // 소수점은 버림
        assertThat(r.avgConfidence()).isEqualByComparingTo("0.9");
        assertThat(r.summary()).isEqualTo("요약");
        assertThat(r.pages()).hasSize(2);
        assertThat(r.pages().get(0).text()).isEqualTo("안녕");
        assertThat(r.pages().get(1).pageNo()).isEqualTo(2);            // pageNum 별칭 지원
        assertThat(r.pages().get(1).text()).isEqualTo("");              // null 텍스트는 빈 문자열
        assertThat(r.pages().get(1).confidence()).isNull();
        assertThat(r.pages().get(1).processingMs()).isEqualTo(0);       // null 은 0
        assertThat(r.images()).hasSize(1);
        assertThat(r.images().get(0).data()).containsExactly(1, 2, 3);  // base64 디코딩
    }

    @Test
    @DisplayName("선택 필드가 없으면 기본값: pageCount=페이지 수, totalMs=0, 공백 요약=null, 모르는 source=OCR")
    void extract_defaults() {
        respond(EXTRACT_PATH, 200, extractJson("OPENAI", """
                "summary":"   ",
                "pages":[{"pageNo":1,"source":"MYSTERY","text":"x"}]"""));

        OcrResult r = client().extract(pdfFile, Engine.OPENAI, false);

        assertThat(r.pageCount()).isEqualTo(1);
        assertThat(r.totalMs()).isEqualTo(0);
        assertThat(r.summary()).isNull();
        assertThat(r.pages().get(0).source()).isEqualTo(PageSource.OCR);
        assertThat(r.images()).isEmpty();
    }

    @Test
    @DisplayName("응답의 엔진이 알 수 없는 값이면 요청한 엔진으로 기록")
    void extract_unknownEngine_usesRequested() {
        respond(EXTRACT_PATH, 200, extractJson("MYSTERY", "\"pages\":[]"));

        assertThat(client().extract(pdfFile, Engine.PDFIUM, false).engine()).isEqualTo(Engine.PDFIUM);
    }

    @Test
    @DisplayName("이미지 base64 가 깨져 있으면 OCR_SERVER_ERROR (페이지 번호 포함)")
    void extract_invalidBase64() {
        respond(EXTRACT_PATH, 200, extractJson("TESSERACT", """
                "pages":[],"images":[{"pageNo":3,"format":"png","width":1,"height":1,"dataBase64":"@@@not-base64@@@"}]"""));

        BusinessException e = expectCode(() -> client().extract(pdfFile, Engine.TESSERACT, true),
                ErrorCode.OCR_SERVER_ERROR);
        assertThat(e.getMessage()).contains("page 3");
    }

    @ParameterizedTest(name = "FastAPI 코드 {0} → {1}")
    @CsvSource({
            "FILE_TOO_LARGE, FILE_TOO_LARGE",
            "UNSUPPORTED_FILE_TYPE, UNSUPPORTED_FILE_TYPE",
            "ENGINE_NOT_ALLOWED, ENGINE_NOT_ALLOWED",
            "INVALID_REQUEST, INVALID_REQUEST",
            "PAGE_LIMIT_EXCEEDED, INVALID_REQUEST",
            "SOMETHING_NEW, OCR_SERVER_ERROR"
    })
    @DisplayName("FastAPI 오류 코드를 Spring ErrorCode 로 매핑 (모르는 코드는 OCR_SERVER_ERROR)")
    void extract_errorCodeMapping(String fastApiCode, ErrorCode expected) {
        respond(EXTRACT_PATH, 400, "{\"success\":false,\"error\":{\"code\":\"" + fastApiCode + "\",\"message\":\"m\"}}");

        BusinessException e = expectCode(() -> client().extract(pdfFile, Engine.AUTO, false), expected);
        assertThat(e.getMessage()).isEqualTo("m");
    }

    @Test
    @DisplayName("JSON 이 아닌 오류 응답(HTTP 500)은 OCR_SERVER_ERROR + 상태 코드 메시지")
    void extract_nonJsonError() {
        server.createContext(EXTRACT_PATH, ex -> {
            byte[] bytes = "Internal Server Error".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(500, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });

        BusinessException e = expectCode(() -> client().extract(pdfFile, Engine.AUTO, false), ErrorCode.OCR_SERVER_ERROR);
        assertThat(e.getMessage()).contains("HTTP 500");
    }

    @Test
    @DisplayName("오류 본문에 메시지가 없으면 상태 코드로 대체 메시지를 만든다")
    void extract_errorWithoutMessage() {
        respond(EXTRACT_PATH, 422, "{\"success\":false,\"error\":{\"code\":\"INVALID_REQUEST\"}}");

        BusinessException e = expectCode(() -> client().extract(pdfFile, Engine.AUTO, false), ErrorCode.INVALID_REQUEST);
        assertThat(e.getMessage()).contains("HTTP 422");
    }

    @Test
    @DisplayName("타임아웃: 응답이 제한시간을 넘기면 OCR_SERVER_ERROR (시간 초과 안내)")
    void extract_timeout() {
        server.createContext(EXTRACT_PATH, ex -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                send(ex, 200, extractJson("AUTO", "\"pages\":[]"));
            } catch (IOException ignored) {
                // 클라이언트가 이미 끊은 경우
            }
        });
        FastApiOcrClient shortTimeout = new FastApiOcrClient(baseUrl, 1);

        BusinessException e = expectCode(() -> shortTimeout.extract(pdfFile, Engine.AUTO, false),
                ErrorCode.OCR_SERVER_ERROR);
        assertThat(e.getMessage()).contains("시간이 초과");
    }

    @Test
    @DisplayName("서버가 꺼져 있으면(연결 거부) OCR_SERVER_ERROR")
    void connectionRefused() {
        FastApiOcrClient c = client();
        server.stop(0);

        expectCode(() -> c.getEngines(), ErrorCode.OCR_SERVER_ERROR);
        expectCode(() -> c.extract(pdfFile, Engine.AUTO, false), ErrorCode.OCR_SERVER_ERROR);
    }
}
