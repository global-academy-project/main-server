package com.example.backend.ocr.service;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.common.type.RunStatus;
import com.example.backend.document.dto.StoredFile;
import com.example.backend.document.entity.Document;
import com.example.backend.document.repository.DocumentRepository;
import com.example.backend.document.storage.LocalFileStorage;
import com.example.backend.ocr.dto.ExportFile;
import com.example.backend.ocr.dto.RunDetail;
import com.example.backend.ocr.dto.RunExport;
import com.example.backend.ocr.dto.RunSummary;
import com.example.backend.ocr.entity.OcrImage;
import com.example.backend.ocr.entity.OcrPage;
import com.example.backend.ocr.entity.OcrRun;
import com.example.backend.ocr.repository.OcrImageRepository;
import com.example.backend.ocr.repository.OcrPageRepository;
import com.example.backend.ocr.repository.OcrRunRepository;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 실행(run) 조회 · 내보내기 · 추출 이미지 (명세 10, 11, 13, 14). 모두 본인 문서만 대상. */
@Service
@Transactional(readOnly = true)
public class OcrRunService {

    private static final MediaType TEXT_UTF8 = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private final DocumentRepository documentRepository;
    private final OcrRunRepository runRepository;
    private final OcrPageRepository pageRepository;
    private final OcrImageRepository imageRepository;
    private final LocalFileStorage storage;

    public OcrRunService(DocumentRepository documentRepository,
                         OcrRunRepository runRepository,
                         OcrPageRepository pageRepository,
                         OcrImageRepository imageRepository,
                         LocalFileStorage storage) {
        this.documentRepository = documentRepository;
        this.runRepository = runRepository;
        this.pageRepository = pageRepository;
        this.imageRepository = imageRepository;
        this.storage = storage;
    }

    /** 10. 실행 이력: 최신순, 페이징 없음 */
    public List<RunSummary> list(Long userId, Long documentId) {
        findOwnedDocument(userId, documentId);
        return runRepository.findByDocumentIdOrderByCreatedAtDesc(documentId).stream()
                .map(RunSummary::from)
                .toList();
    }

    /** 11. 실행 상세 */
    public RunDetail get(Long userId, Long documentId, Long runId) {
        findOwnedDocument(userId, documentId);
        OcrRun run = findRun(documentId, runId);
        return toDetail(run);
    }

    /** 문서 상세(7)의 latestRun 에서도 쓴다. */
    public RunDetail toDetail(OcrRun run) {
        List<OcrPage> pages = pageRepository.findByRunIdOrderByPageNo(run.getId());
        List<OcrImage> images = imageRepository.findByRunIdOrderByPageNo(run.getId());
        return RunDetail.of(run, pages, images);
    }

    /** 13. 내보내기: format=txt|json, DONE 인 run 만 가능 */
    public ExportFile export(Long userId, Long documentId, Long runId, String format) {
        Document document = findOwnedDocument(userId, documentId);
        OcrRun run = findRun(documentId, runId);

        boolean txt = "txt".equals(format);
        boolean json = "json".equals(format);
        if (!txt && !json) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "format 은 txt 또는 json 이어야 합니다.");
        }
        if (run.getStatus() != RunStatus.DONE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "처리가 완료된 실행만 내보낼 수 있습니다.");
        }

        List<OcrPage> pages = pageRepository.findByRunIdOrderByPageNo(run.getId());
        String fileName = baseName(document.getOriginalName()) + "_" + run.getEngine().name() + "." + format;

        if (txt) {
            return new ExportFile(fileName, TEXT_UTF8, toText(pages));
        }
        return new ExportFile(fileName, MediaType.APPLICATION_JSON, RunExport.of(run, pages));
    }

    /** 14. 추출 이미지 */
    public StoredFile getImage(Long userId, Long documentId, Long runId, Long imageId) {
        findOwnedDocument(userId, documentId);
        findRun(documentId, runId);
        OcrImage image = imageRepository.findByIdAndRunId(imageId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.IMAGE_NOT_FOUND));
        if (!storage.exists(image.getFilePath())) {
            throw new BusinessException(ErrorCode.IMAGE_NOT_FOUND);
        }
        return new StoredFile(storage.resolve(image.getFilePath()), "image-" + image.getId() + "." + image.getFormat());
    }

    private Document findOwnedDocument(Long userId, Long documentId) {
        return documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
    }

    private OcrRun findRun(Long documentId, Long runId) {
        return runRepository.findByIdAndDocumentId(runId, documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
    }

    /** 페이지 사이에 "===== Page {n} =====" 구분선 */
    private String toText(List<OcrPage> pages) {
        StringBuilder sb = new StringBuilder();
        for (OcrPage p : pages) {
            sb.append("===== Page ").append(p.getPageNo()).append(" =====\n");
            sb.append(p.getText() == null ? "" : p.getText()).append("\n\n");
        }
        return sb.toString();
    }

    /** 원본 파일명에서 확장자를 제외한 이름 */
    private String baseName(String originalName) {
        int dot = originalName.lastIndexOf('.');
        return dot > 0 ? originalName.substring(0, dot) : originalName;
    }
}
