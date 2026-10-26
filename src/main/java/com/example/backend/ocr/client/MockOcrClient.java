package com.example.backend.ocr.client;

import com.example.backend.common.type.Engine;
import com.example.backend.common.type.NetworkMode;
import com.example.backend.common.type.PageSource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.ocr.client", havingValue = "mock", matchIfMissing = true)
public class MockOcrClient implements OcrClient {

    private static final long DELAY_MS = 1500;
    private static final BigDecimal OCR_CONFIDENCE = new BigDecimal("0.9400");

    @Override
    public EngineInfo getEngines() {
        List<EngineItem> items = new ArrayList<>();
        for (Engine e : Engine.values()) {
            if (e == Engine.AUTO || e == Engine.PDFIUM || e == Engine.TESSERACT || e == Engine.OPENAI) {
                items.add(new EngineItem(e, true, e == Engine.OPENAI, null));
            } else {
                items.add(new EngineItem(e, false, false, "준비 중"));
            }
        }
        return new EngineInfo(NetworkMode.OPEN, Engine.OPENAI, items);
    }

    @Override
    public OcrResult extract(Path pdf, Engine engine, boolean extractImages) {
        long start = System.currentTimeMillis();
        sleep();

        int pageCount = countPages(pdf);
        Engine actual = engine == Engine.AUTO ? Engine.OPENAI : engine;

        boolean hasConfidence = actual == Engine.TESSERACT || actual == Engine.PADDLE;
        PageSource source = actual == Engine.PDFIUM ? PageSource.TEXT_LAYER : PageSource.OCR;
        BigDecimal pageConfidence = (source == PageSource.OCR && hasConfidence) ? OCR_CONFIDENCE : null;

        List<PageResult> pages = new ArrayList<>();
        for (int i = 1; i <= pageCount; i++) {
            pages.add(new PageResult(i, source, "[MOCK] " + i + "페이지 결과입니다.", pageConfidence, 800));
        }
        BigDecimal avg = pageConfidence;

        List<ImageResult> images = new ArrayList<>();
        if (extractImages) {
            images.add(new ImageResult(1, "png", 64, 48, samplePng(64, 48)));
        }

        int totalMs = (int) (System.currentTimeMillis() - start);
        String summary = "[MOCK] 총 " + pageCount + "페이지 문서의 요약 결과입니다.";
        return new OcrResult(actual, NetworkMode.OPEN, pageCount, totalMs, avg, pages, images, summary);
    }

    private void sleep() {
        try {
            Thread.sleep(DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OCR 처리가 중단되었습니다.", e);
        }
    }

    private int countPages(Path pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            throw new IllegalStateException("PDF 를 읽을 수 없습니다.", e);
        }
    }

    private byte[] samplePng(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(220, 230, 245));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(60, 90, 160));
        g.drawRect(2, 2, width - 5, height - 5);
        g.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("샘플 이미지 생성 실패", e);
        }
    }
}
