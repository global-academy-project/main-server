package com.example.backend.nearmiss.kb;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** kb-ingest 호출: 경로·Bearer 토큰·본문 모양, 실패 응답 */
@DisplayName("kb-ingest 클라이언트")
class HttpKbIngestClientTest {

    private static final String TOKEN = "ingest-token";
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private HttpKbIngestClient start(int status, String body, AtomicReference<String> seenBody,
                                     AtomicReference<String> seenAuth) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(HttpKbIngestClient.PATH, ex -> {
            seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return new HttpKbIngestClient("http://127.0.0.1:" + server.getAddress().getPort(), 3, TOKEN, jsonMapper);
    }

    @Test
    @DisplayName("성공: POST /ingest/chunks, Bearer 토큰, {collection, chunks[{point_id, text, payload}]} → indexed")
    void success() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpKbIngestClient client = start(200, "{\"indexed\": 1}", body, auth);

        int indexed = client.upsert("kb_accidents", List.of(new KbIngestClient.Chunk(
                "d91aab42-939e-5a48-9ff5-e915506f3736", "문장", Map.of("site_id", 3, "flagged", false))));

        assertThat(indexed).isEqualTo(1);
        assertThat(auth.get()).isEqualTo("Bearer " + TOKEN);
        JsonNode sent = jsonMapper.readTree(body.get());
        assertThat(sent.path("collection").asString()).isEqualTo("kb_accidents");
        assertThat(sent.path("chunks").get(0).path("point_id").asString()).isEqualTo("d91aab42-939e-5a48-9ff5-e915506f3736");
        assertThat(sent.path("chunks").get(0).path("text").asString()).isEqualTo("문장");
        assertThat(sent.path("chunks").get(0).path("payload").path("site_id").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("2xx 가 아니거나 응답 모양이 다르면 예외, 토큰이 비면 시작하지 않음")
    void failures() throws IOException {
        HttpKbIngestClient unauthorized = start(401, "{\"detail\":\"x\"}", new AtomicReference<>(), new AtomicReference<>());
        assertThatThrownBy(() -> unauthorized.upsert("kb_accidents", List.of(new KbIngestClient.Chunk("id", "t", Map.of()))))
                .hasMessageContaining("401");
        stop();
        HttpKbIngestClient odd = start(200, "{\"ok\": true}", new AtomicReference<>(), new AtomicReference<>());
        assertThatThrownBy(() -> odd.upsert("kb_accidents", List.of(new KbIngestClient.Chunk("id", "t", Map.of()))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new HttpKbIngestClient("http://127.0.0.1:1", 3, " ", jsonMapper))
                .isInstanceOf(IllegalStateException.class);
    }
}
