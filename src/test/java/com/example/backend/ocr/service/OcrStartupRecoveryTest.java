package com.example.backend.ocr.service;

import com.example.backend.common.type.DocumentStatus;
import com.example.backend.common.type.Engine;
import com.example.backend.common.type.RunStatus;
import com.example.backend.document.entity.Document;
import com.example.backend.document.repository.DocumentRepository;
import com.example.backend.ocr.entity.OcrRun;
import com.example.backend.ocr.repository.OcrRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.example.backend.support.TestFixtures.document;
import static com.example.backend.support.TestFixtures.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("OcrStartupRecovery: 재시작 시 남은 PROCESSING 정리")
class OcrStartupRecoveryTest {

    @Test
    @DisplayName("PROCESSING 상태의 run 과 문서를 FAILED 로 바꾸고 사유를 남긴다")
    void recover_marksFailed() {
        OcrRunRepository runRepository = mock(OcrRunRepository.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        OcrRun r = run(20L, 10L, Engine.AUTO);
        Document d = document(10L, 1L, DocumentStatus.PROCESSING);
        when(runRepository.findByStatus(RunStatus.PROCESSING)).thenReturn(List.of(r));
        when(documentRepository.findByStatus(DocumentStatus.PROCESSING)).thenReturn(List.of(d));

        new OcrStartupRecovery(runRepository, documentRepository).recover();

        assertThat(r.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(r.getErrorMessage()).isEqualTo("서버 재시작으로 중단됨");
        assertThat(d.getStatus()).isEqualTo(DocumentStatus.FAILED);
    }

    @Test
    @DisplayName("정리할 것이 없으면 아무 일도 하지 않는다")
    void recover_nothingToDo() {
        OcrRunRepository runRepository = mock(OcrRunRepository.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        when(runRepository.findByStatus(RunStatus.PROCESSING)).thenReturn(List.of());
        when(documentRepository.findByStatus(DocumentStatus.PROCESSING)).thenReturn(List.of());

        assertThatCode(() -> new OcrStartupRecovery(runRepository, documentRepository).recover())
                .doesNotThrowAnyException();
    }
}
