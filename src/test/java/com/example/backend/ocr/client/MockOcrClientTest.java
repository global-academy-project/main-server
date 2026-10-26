package com.example.backend.ocr.client;

import com.example.backend.common.type.Engine;
import com.example.backend.common.type.NetworkMode;
import com.example.backend.common.type.PageSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.example.backend.support.TestFixtures.pdf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MockOcrClient: 개발용 가짜 OCR (엔진별 신뢰도 규칙)")
class MockOcrClientTest {

    private static Path threePagePdf;

    private final MockOcrClient client = new MockOcrClient();

    @BeforeAll
    static void createPdf(@TempDir Path dir) throws IOException {
        threePagePdf = dir.resolve("three.pdf");
        Files.write(threePagePdf, pdf(3));
    }

    @Test
    @DisplayName("엔진 목록: AUTO/PDFIUM/TESSERACT/OPENAI 사용 가능, 나머지는 '준비 중', 기본 엔진 OPENAI, OPENAI 만 외부 전송")
    void engines() {
        EngineInfo info = client.getEngines();

        assertThat(info.networkMode()).isEqualTo(NetworkMode.OPEN);
        assertThat(info.defaultEngine()).isEqualTo(Engine.OPENAI);
        assertThat(info.engines()).hasSize(Engine.values().length);
        for (EngineItem item : info.engines()) {
            boolean expectedAvailable = item.name() == Engine.AUTO || item.name() == Engine.PDFIUM
                    || item.name() == Engine.TESSERACT || item.name() == Engine.OPENAI;
            assertThat(item.available()).as(item.name().name()).isEqualTo(expectedAvailable);
            assertThat(item.external()).as(item.name().name()).isEqualTo(item.name() == Engine.OPENAI);
            if (!expectedAvailable) {
                assertThat(item.reason()).isEqualTo("준비 중");
            }
        }
    }

    @Test
    @DisplayName("TESSERACT: 모든 페이지가 OCR + 신뢰도 0.94, 전체 평균도 0.94")
    void tesseract_hasConfidence() {
        OcrResult r = client.extract(threePagePdf, Engine.TESSERACT, false);

        assertThat(r.engine()).isEqualTo(Engine.TESSERACT);
        assertThat(r.pageCount()).isEqualTo(3);
        assertThat(r.pages()).hasSize(3).allSatisfy(p -> {
            assertThat(p.source()).isEqualTo(PageSource.OCR);
            assertThat(p.confidence()).isEqualByComparingTo("0.9400");
        });
        assertThat(r.avgConfidence()).isEqualByComparingTo("0.9400");
        assertThat(r.pages().get(2).pageNo()).isEqualTo(3);
        assertThat(r.summary()).contains("3페이지");
    }

    @Test
    @DisplayName("PDFIUM: 텍스트 레이어이므로 신뢰도는 페이지·평균 모두 null")
    void pdfium_noConfidence() {
        OcrResult r = client.extract(threePagePdf, Engine.PDFIUM, false);

        assertThat(r.pages()).allSatisfy(p -> {
            assertThat(p.source()).isEqualTo(PageSource.TEXT_LAYER);
            assertThat(p.confidence()).isNull();
        });
        assertThat(r.avgConfidence()).isNull();
    }

    @Test
    @DisplayName("OPENAI: OCR 이지만 신뢰도를 주지 않으므로 null")
    void openai_noConfidence() {
        OcrResult r = client.extract(threePagePdf, Engine.OPENAI, false);

        assertThat(r.pages()).allSatisfy(p -> {
            assertThat(p.source()).isEqualTo(PageSource.OCR);
            assertThat(p.confidence()).isNull();
        });
        assertThat(r.avgConfidence()).isNull();
    }

    @Test
    @DisplayName("AUTO 는 실제 엔진 OPENAI 로 기록된다")
    void auto_resolvesToOpenai() {
        assertThat(client.extract(threePagePdf, Engine.AUTO, false).engine()).isEqualTo(Engine.OPENAI);
    }

    @Test
    @DisplayName("extractImages=true 면 디코딩 가능한 64x48 PNG 한 장, false 면 이미지 없음")
    void images() throws IOException {
        OcrResult with = client.extract(threePagePdf, Engine.TESSERACT, true);
        OcrResult without = client.extract(threePagePdf, Engine.TESSERACT, false);

        assertThat(without.images()).isEmpty();
        assertThat(with.images()).hasSize(1);
        ImageResult img = with.images().get(0);
        assertThat(img.format()).isEqualTo("png");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(img.data()));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(img.width()).isEqualTo(64);
        assertThat(decoded.getHeight()).isEqualTo(img.height()).isEqualTo(48);
    }

    @Test
    @DisplayName("존재하지 않거나 PDF 가 아닌 파일이면 IllegalStateException")
    void unreadablePdf(@TempDir Path dir) throws IOException {
        Path notPdf = dir.resolve("x.pdf");
        Files.writeString(notPdf, "not a pdf");

        assertThatThrownBy(() -> client.extract(dir.resolve("missing.pdf"), Engine.AUTO, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("읽을 수 없습니다");
        assertThatThrownBy(() -> client.extract(notPdf, Engine.AUTO, false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("처리 중 스레드가 인터럽트되면 중단 예외로 바꾸고 인터럽트 상태를 복원한다")
    void interrupted() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> client.extract(threePagePdf, Engine.AUTO, false))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("중단");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // 다른 테스트에 영향이 없도록 상태 초기화
        }
    }
}
