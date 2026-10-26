package com.example.backend.document.service;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.common.type.DocumentStatus;
import com.example.backend.common.type.Engine;
import com.example.backend.document.dto.DocumentDetailResponse;
import com.example.backend.document.dto.DocumentListItem;
import com.example.backend.document.dto.DocumentPageResponse;
import com.example.backend.document.dto.DocumentUploadResponse;
import com.example.backend.document.dto.ReprocessRequest;
import com.example.backend.document.dto.StoredFile;
import com.example.backend.document.entity.Document;
import com.example.backend.document.repository.DocumentRepository;
import com.example.backend.document.storage.LocalFileStorage;
import com.example.backend.ocr.client.EngineInfo;
import com.example.backend.ocr.client.OcrClient;
import com.example.backend.ocr.dto.RunDetail;
import com.example.backend.ocr.entity.OcrRun;
import com.example.backend.ocr.repository.OcrImageRepository;
import com.example.backend.ocr.repository.OcrPageRepository;
import com.example.backend.ocr.repository.OcrRunRepository;
import com.example.backend.ocr.service.OcrRequestedEvent;
import com.example.backend.ocr.service.OcrRunService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DocumentService {

    private static final int MAX_PAGE_SIZE = 50;

    private final DocumentRepository documentRepository;
    private final OcrRunRepository runRepository;
    private final OcrPageRepository pageRepository;
    private final OcrImageRepository imageRepository;
    private final LocalFileStorage storage;
    private final PdfValidator pdfValidator;
    private final OcrClient ocrClient;
    private final OcrRunService runService;
    private final ApplicationEventPublisher eventPublisher;

    public DocumentService(DocumentRepository documentRepository,
                           OcrRunRepository runRepository,
                           OcrPageRepository pageRepository,
                           OcrImageRepository imageRepository,
                           LocalFileStorage storage,
                           PdfValidator pdfValidator,
                           OcrClient ocrClient,
                           OcrRunService runService,
                           ApplicationEventPublisher eventPublisher) {
        this.documentRepository = documentRepository;
        this.runRepository = runRepository;
        this.pageRepository = pageRepository;
        this.imageRepository = imageRepository;
        this.storage = storage;
        this.pdfValidator = pdfValidator;
        this.ocrClient = ocrClient;
        this.runService = runService;
        this.eventPublisher = eventPublisher;
    }

    /** 5. 업로드: 검증 -> 파일 저장 -> document/run 생성 -> 커밋 후 비동기 OCR 시작. */
    @Transactional
    public DocumentUploadResponse upload(Long userId, MultipartFile file, Engine engine, boolean extractImages) {
        int pageCount = pdfValidator.validate(file);
        EngineInfo engines = ocrClient.getEngines();
        checkEngine(engine, engines);

        String storedPath = storage.store(file, userId);
        // DB 반영이 롤백되면 방금 저장한 파일도 지운다 (고아 파일 방지)
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    storage.delete(storedPath);
                }
            }
        });

        Document document = new Document(userId, cleanName(file.getOriginalFilename()), storedPath, file.getSize());
        document.updatePageCount(pageCount);
        document.changeStatus(DocumentStatus.PROCESSING);
        document = documentRepository.save(document);

        OcrRun run = runRepository.save(new OcrRun(document.getId(), engine, engines.networkMode(), extractImages));

        eventPublisher.publishEvent(new OcrRequestedEvent(run.getId(), document.getId()));
        return DocumentUploadResponse.of(document, run);
    }

    /** 12. 재처리: 기존 run 은 유지하고 새 run 을 추가한다. */
    @Transactional
    public DocumentUploadResponse reprocess(Long userId, Long documentId, ReprocessRequest request) {
        if (request == null || request.engine() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "engine 은 필수입니다.");
        }
        Document document = findOwned(userId, documentId);
        if (document.getStatus() == DocumentStatus.PROCESSING) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSING);
        }
        if (!storage.exists(document.getStoredPath())) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        EngineInfo engines = ocrClient.getEngines();
        checkEngine(request.engine(), engines);

        boolean extractImages = Boolean.TRUE.equals(request.extractImages());
        OcrRun run = runRepository.save(new OcrRun(document.getId(), request.engine(), engines.networkMode(), extractImages));
        document.changeStatus(DocumentStatus.PROCESSING);

        eventPublisher.publishEvent(new OcrRequestedEvent(run.getId(), document.getId()));
        return DocumentUploadResponse.of(document, run);
    }

    /** 6. 목록: page 0부터, size 1~50, sort=createdAt,desc|asc, status 필터 */
    @Transactional(readOnly = true)
    public DocumentPageResponse list(Long userId, int page, int size, DocumentStatus status, String sort) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "page 는 0 이상, size 는 1~" + MAX_PAGE_SIZE + " 이어야 합니다.");
        }
        PageRequest pageable = PageRequest.of(page, size, parseSort(sort));

        Page<Document> documents = status == null
                ? documentRepository.findByUserId(userId, pageable)
                : documentRepository.findByUserIdAndStatus(userId, status, pageable);

        // 문서마다 최신 run 을 따로 조회하면 N+1 이므로 한 번에 가져와서 문서별 최신 run 을 고른다
        List<Long> ids = documents.getContent().stream().map(Document::getId).toList();
        Map<Long, OcrRun> latest = new HashMap<>();
        if (!ids.isEmpty()) {
            for (OcrRun r : runRepository.findByDocumentIdIn(ids)) {
                latest.merge(r.getDocumentId(), r, (a, b) -> a.getId() > b.getId() ? a : b);
            }
        }
        return DocumentPageResponse.from(documents.map(d -> DocumentListItem.of(d, latest.get(d.getId()))));
    }

    /** 7. 상세 (폴링 대상): 최신 run 의 pages/images 까지 포함 */
    @Transactional(readOnly = true)
    public DocumentDetailResponse get(Long userId, Long documentId) {
        Document document = findOwned(userId, documentId);
        RunDetail latest = runRepository.findFirstByDocumentIdOrderByIdDesc(documentId)
                .map(runService::toDetail)
                .orElse(null);
        return DocumentDetailResponse.of(document, latest);
    }

    /** 9. 원본 PDF */
    @Transactional(readOnly = true)
    public StoredFile getFile(Long userId, Long documentId) {
        Document document = findOwned(userId, documentId);
        if (!storage.exists(document.getStoredPath())) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        return new StoredFile(storage.resolve(document.getStoredPath()), document.getOriginalName());
    }

    /** 8. 삭제: 문서, 하위 데이터(run/page/image), 저장 파일 */
    @Transactional
    public void delete(Long userId, Long documentId) {
        Document document = findOwned(userId, documentId);
        if (document.getStatus() == DocumentStatus.PROCESSING) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSING);
        }
        String storedPath = document.getStoredPath();

        for (OcrRun run : runRepository.findByDocumentIdOrderByCreatedAtDesc(documentId)) {
            pageRepository.deleteByRunId(run.getId());
            imageRepository.deleteByRunId(run.getId());
            runRepository.delete(run);
        }
        documentRepository.delete(document);

        // DB 삭제가 커밋된 뒤에만 파일을 지운다
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storage.delete(storedPath);
                storage.deleteDirectory("images/" + documentId);
            }
        });
    }

    /** 남의 문서는 존재하지 않는 것처럼 404. */
    private Document findOwned(Long userId, Long documentId) {
        return documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
    }

    /** AUTO 는 항상 허용. 그 외 엔진은 OCR 서버가 알려준 목록에서 available=true 여야 한다. */
    private void checkEngine(Engine engine, EngineInfo info) {
        if (engine == Engine.AUTO) {
            return;
        }
        boolean allowed = info.engines().stream()
                .anyMatch(e -> e.name() == engine && e.available());
        if (!allowed) {
            throw new BusinessException(ErrorCode.ENGINE_NOT_ALLOWED);
        }
    }

    private Sort parseSort(String sort) {
        String[] parts = sort == null ? new String[0] : sort.split(",");
        if (parts.length != 2 || !"createdAt".equals(parts[0].trim())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "sort 는 createdAt,desc 또는 createdAt,asc 만 가능합니다.");
        }
        Sort.Direction direction;
        if ("asc".equalsIgnoreCase(parts[1].trim())) {
            direction = Sort.Direction.ASC;
        } else if ("desc".equalsIgnoreCase(parts[1].trim())) {
            direction = Sort.Direction.DESC;
        } else {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "sort 는 createdAt,desc 또는 createdAt,asc 만 가능합니다.");
        }
        // 같은 시각의 문서가 있어도 순서가 흔들리지 않게 id 를 보조 정렬로 둔다
        return Sort.by(direction, "createdAt").and(Sort.by(direction, "id"));
    }

    /** 경로 구분자를 제거하고 255자로 자른다. */
    private String cleanName(String original) {
        String name = (original == null || original.isBlank()) ? "document.pdf" : original;
        int idx = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(idx + 1);
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }
}
