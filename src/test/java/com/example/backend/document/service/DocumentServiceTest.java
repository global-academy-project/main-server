package com.example.backend.document.service;

import com.example.backend.common.exception.ErrorCode;
import com.example.backend.common.type.DocumentStatus;
import com.example.backend.common.type.Engine;
import com.example.backend.common.type.NetworkMode;
import com.example.backend.document.dto.DocumentDetailResponse;
import com.example.backend.document.dto.DocumentPageResponse;
import com.example.backend.document.dto.DocumentUploadResponse;
import com.example.backend.document.dto.ReprocessRequest;
import com.example.backend.document.dto.StoredFile;
import com.example.backend.document.entity.Document;
import com.example.backend.document.repository.DocumentRepository;
import com.example.backend.document.storage.LocalFileStorage;
import com.example.backend.ocr.client.EngineInfo;
import com.example.backend.ocr.client.EngineItem;
import com.example.backend.ocr.client.OcrClient;
import com.example.backend.ocr.dto.RunDetail;
import com.example.backend.ocr.entity.OcrRun;
import com.example.backend.ocr.repository.OcrImageRepository;
import com.example.backend.ocr.repository.OcrPageRepository;
import com.example.backend.ocr.repository.OcrRunRepository;
import com.example.backend.ocr.service.OcrRequestedEvent;
import com.example.backend.ocr.service.OcrRunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.example.backend.support.TestFixtures.document;
import static com.example.backend.support.TestFixtures.expectCode;
import static com.example.backend.support.TestFixtures.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("DocumentService: 업로드 / 재처리 / 목록 / 상세 / 삭제")
class DocumentServiceTest {

    private static final Long USER = 1L;

    private DocumentRepository documentRepository;
    private OcrRunRepository runRepository;
    private OcrPageRepository pageRepository;
    private OcrImageRepository imageRepository;
    private LocalFileStorage storage;
    private PdfValidator pdfValidator;
    private OcrClient ocrClient;
    private OcrRunService runService;
    private ApplicationEventPublisher publisher;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        runRepository = mock(OcrRunRepository.class);
        pageRepository = mock(OcrPageRepository.class);
        imageRepository = mock(OcrImageRepository.class);
        storage = mock(LocalFileStorage.class);
        pdfValidator = mock(PdfValidator.class);
        ocrClient = mock(OcrClient.class);
        runService = mock(OcrRunService.class);
        publisher = mock(ApplicationEventPublisher.class);
        service = new DocumentService(documentRepository, runRepository, pageRepository, imageRepository,
                storage, pdfValidator, ocrClient, runService, publisher);
        // upload/delete 는 트랜잭션 콜백을 등록하므로 테스트에서도 동기화를 켜 둔다
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private static EngineInfo engines(boolean tesseractAvailable) {
        return new EngineInfo(NetworkMode.OPEN, Engine.AUTO, List.of(
                new EngineItem(Engine.AUTO, true, false, null),
                new EngineItem(Engine.TESSERACT, tesseractAvailable, false, tesseractAvailable ? null : "준비 중"),
                new EngineItem(Engine.OPENAI, true, true, null)));
    }

    private static MockMultipartFile upload(String name) {
        return new MockMultipartFile("file", name, "application/pdf", new byte[]{1, 2, 3});
    }

    /** save 가 id 를 채워 돌려주도록 모의 (실제 DB 의 IDENTITY 동작 흉내) */
    private void stubSaves() {
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> {
            Document d = inv.getArgument(0);
            ReflectionTestUtils.setField(d, "id", 10L);
            return d;
        });
        when(runRepository.save(any(OcrRun.class))).thenAnswer(inv -> {
            OcrRun r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 20L);
            return r;
        });
    }

    private void commit() {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
            s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
    }

    private void rollback() {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
    }

    @Nested
    @DisplayName("upload")
    class Upload {

        @Test
        @DisplayName("성공: 문서(PROCESSING, 페이지 수)와 run 을 만들고 OCR 시작 이벤트를 발행")
        void success() {
            when(pdfValidator.validate(any())).thenReturn(3);
            when(ocrClient.getEngines()).thenReturn(engines(true));
            when(storage.store(any(), eq(USER))).thenReturn("pdfs/1/a.pdf");
            stubSaves();

            DocumentUploadResponse res = service.upload(USER, upload("a.pdf"), Engine.TESSERACT, true);

            assertThat(res.documentId()).isEqualTo(10L);
            assertThat(res.runId()).isEqualTo(20L);
            assertThat(res.status()).isEqualTo(DocumentStatus.PROCESSING);
            assertThat(res.engine()).isEqualTo(Engine.TESSERACT);
            assertThat(res.networkMode()).isEqualTo(NetworkMode.OPEN);

            ArgumentCaptor<Document> doc = ArgumentCaptor.forClass(Document.class);
            verify(documentRepository).save(doc.capture());
            assertThat(doc.getValue().getPageCount()).isEqualTo(3);
            assertThat(doc.getValue().getStoredPath()).isEqualTo("pdfs/1/a.pdf");
            assertThat(doc.getValue().getFileSize()).isEqualTo(3L);

            ArgumentCaptor<OcrRun> run = ArgumentCaptor.forClass(OcrRun.class);
            verify(runRepository).save(run.capture());
            assertThat(run.getValue().isExtractImages()).isTrue();

            verify(publisher).publishEvent(new OcrRequestedEvent(20L, 10L));
        }

        @Test
        @DisplayName("파일 검증에 실패하면 OCR 서버·저장소·DB 를 건드리지 않는다")
        void validationFailure_touchesNothing() {
            when(pdfValidator.validate(any())).thenThrow(
                    new com.example.backend.common.exception.BusinessException(ErrorCode.FILE_TOO_LARGE));

            expectCode(() -> service.upload(USER, upload("a.pdf"), Engine.AUTO, false), ErrorCode.FILE_TOO_LARGE);

            verifyNoInteractions(ocrClient, storage, documentRepository, runRepository, publisher);
        }

        @Test
        @DisplayName("사용 불가 엔진(available=false)이면 ENGINE_NOT_ALLOWED, 파일은 저장하지 않는다")
        void unavailableEngine_rejected() {
            when(pdfValidator.validate(any())).thenReturn(1);
            when(ocrClient.getEngines()).thenReturn(engines(false));

            expectCode(() -> service.upload(USER, upload("a.pdf"), Engine.TESSERACT, false),
                    ErrorCode.ENGINE_NOT_ALLOWED);

            verify(storage, never()).store(any(), anyLong());
            verifyNoInteractions(publisher);
        }

        @Test
        @DisplayName("엔진 목록에 없는 엔진(OLLAMA)도 ENGINE_NOT_ALLOWED")
        void unknownEngine_rejected() {
            when(pdfValidator.validate(any())).thenReturn(1);
            when(ocrClient.getEngines()).thenReturn(engines(true));

            expectCode(() -> service.upload(USER, upload("a.pdf"), Engine.OLLAMA, false),
                    ErrorCode.ENGINE_NOT_ALLOWED);
        }

        @Test
        @DisplayName("AUTO 는 엔진 목록이 비어 있어도 항상 허용")
        void auto_alwaysAllowed() {
            when(pdfValidator.validate(any())).thenReturn(1);
            when(ocrClient.getEngines()).thenReturn(new EngineInfo(NetworkMode.CLOSED, Engine.AUTO, List.of()));
            when(storage.store(any(), eq(USER))).thenReturn("pdfs/1/a.pdf");
            stubSaves();

            DocumentUploadResponse res = service.upload(USER, upload("a.pdf"), Engine.AUTO, false);

            assertThat(res.networkMode()).isEqualTo(NetworkMode.CLOSED);
        }

        @Test
        @DisplayName("OCR 서버 장애로 엔진 조회가 실패하면 OCR_SERVER_ERROR 를 그대로 전달하고 저장하지 않는다")
        void ocrServerDown_propagates() {
            when(pdfValidator.validate(any())).thenReturn(1);
            when(ocrClient.getEngines()).thenThrow(
                    new com.example.backend.common.exception.BusinessException(ErrorCode.OCR_SERVER_ERROR));

            expectCode(() -> service.upload(USER, upload("a.pdf"), Engine.AUTO, false), ErrorCode.OCR_SERVER_ERROR);

            verify(storage, never()).store(any(), anyLong());
        }

        @Test
        @DisplayName("트랜잭션이 롤백되면 방금 저장한 파일을 지운다 (고아 파일 방지)")
        void rollback_deletesStoredFile() {
            when(pdfValidator.validate(any())).thenReturn(1);
            when(ocrClient.getEngines()).thenReturn(engines(true));
            when(storage.store(any(), eq(USER))).thenReturn("pdfs/1/a.pdf");
            stubSaves();

            service.upload(USER, upload("a.pdf"), Engine.AUTO, false);
            rollback();

            verify(storage).delete("pdfs/1/a.pdf");
        }

        @Test
        @DisplayName("커밋되면 저장한 파일을 지우지 않는다")
        void commit_keepsStoredFile() {
            when(pdfValidator.validate(any())).thenReturn(1);
            when(ocrClient.getEngines()).thenReturn(engines(true));
            when(storage.store(any(), eq(USER))).thenReturn("pdfs/1/a.pdf");
            stubSaves();

            service.upload(USER, upload("a.pdf"), Engine.AUTO, false);
            commit();

            verify(storage, never()).delete(any());
        }

        @ParameterizedTest(name = "원본명 \"{0}\" → \"{1}\"")
        @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
                "C:\\temp\\scan\\보고서.pdf|보고서.pdf",
                "../../etc/passwd.pdf|passwd.pdf",
                "dir/sub/a.pdf|a.pdf",
                " |document.pdf"
        })
        @DisplayName("파일명에서 경로 구분자를 제거하고, 비어 있으면 기본 이름을 쓴다")
        void fileName_isCleaned(String original, String expected) {
            when(pdfValidator.validate(any())).thenReturn(1);
            when(ocrClient.getEngines()).thenReturn(engines(true));
            when(storage.store(any(), eq(USER))).thenReturn("pdfs/1/x.pdf");
            stubSaves();

            service.upload(USER, upload(original), Engine.AUTO, false);

            ArgumentCaptor<Document> doc = ArgumentCaptor.forClass(Document.class);
            verify(documentRepository).save(doc.capture());
            assertThat(doc.getValue().getOriginalName()).isEqualTo(expected);
        }

        @Test
        @DisplayName("255자를 넘는 파일명은 뒤쪽 255자만 남긴다 (확장자 보존)")
        void longFileName_truncatedKeepingExtension() {
            when(pdfValidator.validate(any())).thenReturn(1);
            when(ocrClient.getEngines()).thenReturn(engines(true));
            when(storage.store(any(), eq(USER))).thenReturn("pdfs/1/x.pdf");
            stubSaves();

            service.upload(USER, upload("가".repeat(300) + ".pdf"), Engine.AUTO, false);

            ArgumentCaptor<Document> doc = ArgumentCaptor.forClass(Document.class);
            verify(documentRepository).save(doc.capture());
            assertThat(doc.getValue().getOriginalName()).hasSize(255).endsWith(".pdf");
        }
    }

    @Nested
    @DisplayName("reprocess")
    class Reprocess {

        private void givenOwned(DocumentStatus status) {
            when(documentRepository.findByIdAndUserId(10L, USER))
                    .thenReturn(Optional.of(document(10L, USER, status)));
            when(runRepository.save(any(OcrRun.class))).thenAnswer(inv -> {
                OcrRun r = inv.getArgument(0);
                ReflectionTestUtils.setField(r, "id", 21L);
                return r;
            });
        }

        @Test
        @DisplayName("요청 본문이 없거나 engine 이 없으면 INVALID_REQUEST")
        void missingEngine_rejected() {
            expectCode(() -> service.reprocess(USER, 10L, null), ErrorCode.INVALID_REQUEST);
            expectCode(() -> service.reprocess(USER, 10L, new ReprocessRequest(null, true)), ErrorCode.INVALID_REQUEST);
            verifyNoInteractions(documentRepository);
        }

        @Test
        @DisplayName("없는 문서 / 남의 문서는 DOCUMENT_NOT_FOUND")
        void notOwned_notFound() {
            when(documentRepository.findByIdAndUserId(10L, USER)).thenReturn(Optional.empty());

            expectCode(() -> service.reprocess(USER, 10L, new ReprocessRequest(Engine.AUTO, false)),
                    ErrorCode.DOCUMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("처리 중인 문서는 ALREADY_PROCESSING, run 을 새로 만들지 않는다")
        void processing_rejected() {
            givenOwned(DocumentStatus.PROCESSING);

            expectCode(() -> service.reprocess(USER, 10L, new ReprocessRequest(Engine.AUTO, false)),
                    ErrorCode.ALREADY_PROCESSING);

            verify(runRepository, never()).save(any());
            verifyNoInteractions(publisher);
        }

        @Test
        @DisplayName("원본 파일이 사라졌으면 FILE_NOT_FOUND")
        void missingFile_rejected() {
            givenOwned(DocumentStatus.DONE);
            when(storage.exists(any())).thenReturn(false);

            expectCode(() -> service.reprocess(USER, 10L, new ReprocessRequest(Engine.AUTO, false)),
                    ErrorCode.FILE_NOT_FOUND);
        }

        @Test
        @DisplayName("사용 불가 엔진이면 ENGINE_NOT_ALLOWED, 문서 상태는 그대로")
        void unavailableEngine_rejected() {
            givenOwned(DocumentStatus.DONE);
            when(storage.exists(any())).thenReturn(true);
            when(ocrClient.getEngines()).thenReturn(engines(false));

            expectCode(() -> service.reprocess(USER, 10L, new ReprocessRequest(Engine.TESSERACT, false)),
                    ErrorCode.ENGINE_NOT_ALLOWED);

            verify(runRepository, never()).save(any());
        }

        @Test
        @DisplayName("성공: 새 run 을 추가하고 문서를 PROCESSING 으로 바꾸며 이벤트 발행 (extractImages 생략 시 false)")
        void success_extractImagesDefaultsToFalse() {
            givenOwned(DocumentStatus.DONE);
            when(storage.exists(any())).thenReturn(true);
            when(ocrClient.getEngines()).thenReturn(engines(true));

            DocumentUploadResponse res = service.reprocess(USER, 10L, new ReprocessRequest(Engine.OPENAI, null));

            assertThat(res.runId()).isEqualTo(21L);
            assertThat(res.status()).isEqualTo(DocumentStatus.PROCESSING);
            ArgumentCaptor<OcrRun> run = ArgumentCaptor.forClass(OcrRun.class);
            verify(runRepository).save(run.capture());
            assertThat(run.getValue().isExtractImages()).isFalse();
            assertThat(run.getValue().getEngine()).isEqualTo(Engine.OPENAI);
            verify(publisher).publishEvent(new OcrRequestedEvent(21L, 10L));
        }

        @Test
        @DisplayName("실패(FAILED)한 문서도 재처리할 수 있고, extractImages=true 가 그대로 반영된다")
        void failedDocument_canBeReprocessed() {
            givenOwned(DocumentStatus.FAILED);
            when(storage.exists(any())).thenReturn(true);
            when(ocrClient.getEngines()).thenReturn(engines(true));

            service.reprocess(USER, 10L, new ReprocessRequest(Engine.AUTO, true));

            ArgumentCaptor<OcrRun> run = ArgumentCaptor.forClass(OcrRun.class);
            verify(runRepository).save(run.capture());
            assertThat(run.getValue().isExtractImages()).isTrue();
        }
    }

    @Nested
    @DisplayName("list")
    class ListDocuments {

        @ParameterizedTest(name = "page={0}, size={1}")
        @org.junit.jupiter.params.provider.CsvSource({"-1,10", "0,0", "0,51", "0,-5"})
        @DisplayName("page 는 0 이상, size 는 1~50 이 아니면 INVALID_REQUEST")
        void invalidPaging(int page, int size) {
            expectCode(() -> service.list(USER, page, size, null, "createdAt,desc"), ErrorCode.INVALID_REQUEST);
            verifyNoInteractions(documentRepository);
        }

        @ParameterizedTest(name = "sort=\"{0}\"")
        @NullAndEmptySource
        @ValueSource(strings = {"name,desc", "createdAt", "createdAt,up", "createdAt,desc,extra", "id,asc"})
        @DisplayName("createdAt,asc|desc 이외의 sort 는 INVALID_REQUEST")
        void invalidSort(String sort) {
            expectCode(() -> service.list(USER, 0, 10, null, sort), ErrorCode.INVALID_REQUEST);
        }

        @Test
        @DisplayName("경계: size=50 은 허용")
        void maxSize_allowed() {
            when(documentRepository.findByUserId(eq(USER), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

            DocumentPageResponse res = service.list(USER, 0, 50, null, "createdAt,desc");

            assertThat(res.size()).isEqualTo(50);
            assertThat(res.content()).isEmpty();
        }

        @Test
        @DisplayName("정렬은 createdAt + id 보조 정렬이며 방향/대소문자를 지킨다")
        void sortDirection_withTieBreaker() {
            when(documentRepository.findByUserId(eq(USER), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

            service.list(USER, 0, 10, null, "createdAt,ASC");

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(documentRepository).findByUserId(eq(USER), pageable.capture());
            Sort sort = pageable.getValue().getSort();
            assertThat(sort.getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.ASC);
            assertThat(sort.getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.ASC);
        }

        @Test
        @DisplayName("status 필터가 있으면 상태별 조회를, 없으면 전체 조회를 쓴다")
        void statusFilter() {
            when(documentRepository.findByUserIdAndStatus(eq(USER), eq(DocumentStatus.DONE), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

            service.list(USER, 0, 10, DocumentStatus.DONE, "createdAt,desc");

            verify(documentRepository).findByUserIdAndStatus(eq(USER), eq(DocumentStatus.DONE), any(Pageable.class));
            verify(documentRepository, never()).findByUserId(anyLong(), any(Pageable.class));
        }

        @Test
        @DisplayName("문서마다 가장 큰 id 의 run 을 최신으로 고르고, run 이 없으면 null")
        void latestRunPerDocument() {
            Document d1 = document(1L, USER, DocumentStatus.DONE);
            Document d2 = document(2L, USER, DocumentStatus.UPLOADED);
            when(documentRepository.findByUserId(eq(USER), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(d1, d2), PageRequest.of(0, 10), 2));
            // 일부러 순서를 섞어서 반환
            when(runRepository.findByDocumentIdIn(any())).thenReturn(List.of(
                    run(9L, 1L, Engine.OPENAI), run(5L, 1L, Engine.TESSERACT)));

            DocumentPageResponse res = service.list(USER, 0, 10, null, "createdAt,desc");

            assertThat(res.content()).hasSize(2);
            assertThat(res.content().get(0).latestRunId()).isEqualTo(9L);
            assertThat(res.content().get(0).latestEngine()).isEqualTo(Engine.OPENAI);
            assertThat(res.content().get(1).latestRunId()).isNull();
            assertThat(res.content().get(1).latestEngine()).isNull();
        }

        @Test
        @DisplayName("문서가 없으면 run 조회 쿼리를 보내지 않는다 (빈 IN 절 방지)")
        void emptyPage_skipsRunQuery() {
            when(documentRepository.findByUserId(eq(USER), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

            service.list(USER, 0, 10, null, "createdAt,desc");

            verify(runRepository, never()).findByDocumentIdIn(any());
        }
    }

    @Nested
    @DisplayName("get / getFile")
    class GetOne {

        @Test
        @DisplayName("run 이 아직 없는 문서는 latestRun 이 null")
        void noRun_latestRunNull() {
            when(documentRepository.findByIdAndUserId(10L, USER))
                    .thenReturn(Optional.of(document(10L, USER, DocumentStatus.UPLOADED)));
            when(runRepository.findFirstByDocumentIdOrderByIdDesc(10L)).thenReturn(Optional.empty());

            DocumentDetailResponse res = service.get(USER, 10L);

            assertThat(res.latestRun()).isNull();
            assertThat(res.status()).isEqualTo(DocumentStatus.UPLOADED);
        }

        @Test
        @DisplayName("최신 run 이 있으면 RunDetail 로 변환해 포함")
        void withRun_includesDetail() {
            OcrRun latest = run(20L, 10L, Engine.AUTO);
            RunDetail detail = RunDetail.of(latest, List.of(), List.of());
            when(documentRepository.findByIdAndUserId(10L, USER))
                    .thenReturn(Optional.of(document(10L, USER, DocumentStatus.PROCESSING)));
            when(runRepository.findFirstByDocumentIdOrderByIdDesc(10L)).thenReturn(Optional.of(latest));
            when(runService.toDetail(latest)).thenReturn(detail);

            assertThat(service.get(USER, 10L).latestRun()).isSameAs(detail);
        }

        @Test
        @DisplayName("남의 문서 조회는 DOCUMENT_NOT_FOUND (존재 여부를 드러내지 않음)")
        void otherUsersDocument_notFound() {
            when(documentRepository.findByIdAndUserId(10L, 99L)).thenReturn(Optional.empty());

            expectCode(() -> service.get(99L, 10L), ErrorCode.DOCUMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("원본 PDF: 저장 파일이 없으면 FILE_NOT_FOUND, 있으면 경로와 원본명 반환")
        void getFile() {
            Document d = document(10L, USER, DocumentStatus.DONE);
            when(documentRepository.findByIdAndUserId(10L, USER)).thenReturn(Optional.of(d));
            when(storage.exists(d.getStoredPath())).thenReturn(false);
            expectCode(() -> service.getFile(USER, 10L), ErrorCode.FILE_NOT_FOUND);

            when(storage.exists(d.getStoredPath())).thenReturn(true);
            when(storage.resolve(d.getStoredPath())).thenReturn(Path.of("/data/a.pdf"));
            StoredFile file = service.getFile(USER, 10L);
            assertThat(file.path()).isEqualTo(Path.of("/data/a.pdf"));
            assertThat(file.originalName()).isEqualTo("보고서.pdf");
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("처리 중인 문서는 ALREADY_PROCESSING, 아무것도 지우지 않는다")
        void processing_rejected() {
            when(documentRepository.findByIdAndUserId(10L, USER))
                    .thenReturn(Optional.of(document(10L, USER, DocumentStatus.PROCESSING)));

            expectCode(() -> service.delete(USER, 10L), ErrorCode.ALREADY_PROCESSING);

            verify(documentRepository, never()).delete(any());
            verifyNoInteractions(pageRepository, imageRepository);
        }

        @Test
        @DisplayName("남의 문서는 DOCUMENT_NOT_FOUND, 아무것도 지우지 않는다")
        void otherUsers_notFound() {
            when(documentRepository.findByIdAndUserId(10L, 99L)).thenReturn(Optional.empty());

            expectCode(() -> service.delete(99L, 10L), ErrorCode.DOCUMENT_NOT_FOUND);

            verify(documentRepository, never()).delete(any());
        }

        @Test
        @DisplayName("run 마다 page → image → run 순으로 지우고 마지막에 문서를 지운다")
        void deletesChildrenThenDocument() {
            Document d = document(10L, USER, DocumentStatus.DONE);
            OcrRun r1 = run(20L, 10L, Engine.AUTO);
            OcrRun r2 = run(21L, 10L, Engine.OPENAI);
            when(documentRepository.findByIdAndUserId(10L, USER)).thenReturn(Optional.of(d));
            when(runRepository.findByDocumentIdOrderByCreatedAtDesc(10L)).thenReturn(List.of(r2, r1));

            service.delete(USER, 10L);

            var order = inOrder(pageRepository, imageRepository, runRepository, documentRepository);
            order.verify(pageRepository).deleteByRunId(21L);
            order.verify(imageRepository).deleteByRunId(21L);
            order.verify(runRepository).delete(r2);
            order.verify(pageRepository).deleteByRunId(20L);
            order.verify(imageRepository).deleteByRunId(20L);
            order.verify(runRepository).delete(r1);
            order.verify(documentRepository).delete(d);
        }

        @Test
        @DisplayName("저장 파일은 커밋 이후에만 지우고, 롤백되면 지우지 않는다")
        void filesDeletedOnlyAfterCommit() {
            Document d = document(10L, USER, DocumentStatus.FAILED);
            when(documentRepository.findByIdAndUserId(10L, USER)).thenReturn(Optional.of(d));
            when(runRepository.findByDocumentIdOrderByCreatedAtDesc(10L)).thenReturn(List.of());

            service.delete(USER, 10L);
            verify(storage, never()).delete(any());

            rollback();
            verify(storage, never()).delete(any());

            commit();
            verify(storage).delete(d.getStoredPath());
            verify(storage).deleteDirectory("images/10");
        }
    }
}
