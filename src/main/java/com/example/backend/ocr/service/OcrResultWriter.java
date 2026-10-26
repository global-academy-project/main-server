package com.example.backend.ocr.service;

import com.example.backend.common.type.DocumentStatus;
import com.example.backend.document.entity.Document;
import com.example.backend.document.repository.DocumentRepository;
import com.example.backend.document.storage.LocalFileStorage;
import com.example.backend.ocr.client.ImageResult;
import com.example.backend.ocr.client.OcrResult;
import com.example.backend.ocr.client.PageResult;
import com.example.backend.ocr.entity.OcrImage;
import com.example.backend.ocr.entity.OcrPage;
import com.example.backend.ocr.entity.OcrRun;
import com.example.backend.ocr.repository.OcrImageRepository;
import com.example.backend.ocr.repository.OcrPageRepository;
import com.example.backend.ocr.repository.OcrRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * OCR 결과를 DB 와 저장소에 반영한다. (명세 §4 "Spring이 응답을 DB에 매핑하는 규칙")
 * 한 트랜잭션으로 묶어서 run / document / pages / images 가 같이 성공하거나 같이 실패하게 한다.
 * 이 클래스는 리스너와 분리된 빈이어야 @Transactional 이 적용된다.
 */
@Service
public class OcrResultWriter {

    private final OcrRunRepository runRepository;
    private final DocumentRepository documentRepository;
    private final OcrPageRepository pageRepository;
    private final OcrImageRepository imageRepository;
    private final LocalFileStorage storage;

    public OcrResultWriter(OcrRunRepository runRepository,
                           DocumentRepository documentRepository,
                           OcrPageRepository pageRepository,
                           OcrImageRepository imageRepository,
                           LocalFileStorage storage) {
        this.runRepository = runRepository;
        this.documentRepository = documentRepository;
        this.pageRepository = pageRepository;
        this.imageRepository = imageRepository;
        this.storage = storage;
    }

    @Transactional
    public void saveSuccess(Long runId, Long documentId, OcrResult result) {
        OcrRun run = runRepository.findById(runId).orElseThrow();
        Document document = documentRepository.findById(documentId).orElseThrow();

        // engine, networkMode, totalMs, avgConfidence -> ocr_runs
        run.applyResult(result.engine(), result.networkMode(), result.totalMs(),
                result.avgConfidence(), result.summary());

        // pageCount -> documents.page_count, 문서 상태 = 최신 run 상태
        document.updatePageCount(result.pageCount());
        document.changeStatus(DocumentStatus.DONE);

        // pages[] -> ocr_pages
        List<OcrPage> pages = new ArrayList<>();
        for (PageResult p : result.pages()) {
            pages.add(new OcrPage(runId, p.pageNo(), p.source(), p.text(), p.confidence(), p.processingMs()));
        }
        pageRepository.saveAll(pages);

        // images[] -> 파일로 저장, ocr_images 에는 경로만
        int seq = 1;
        for (ImageResult img : result.images()) {
            String format = img.format() == null || img.format().isBlank()
                    ? "png" : img.format().trim().toLowerCase(java.util.Locale.ROOT);
            String path = "images/" + documentId + "/" + runId + "/" + seq + "." + format;
            storage.storeBytes(path, img.data());
            imageRepository.save(new OcrImage(runId, img.pageNo(), path, format, img.width(), img.height()));
            seq++;
        }
    }

    @Transactional
    public void saveFailure(Long runId, Long documentId, String message) {
        runRepository.findById(runId).ifPresent(run -> run.fail(message));
        documentRepository.findById(documentId).ifPresent(d -> d.changeStatus(DocumentStatus.FAILED));
    }
}
