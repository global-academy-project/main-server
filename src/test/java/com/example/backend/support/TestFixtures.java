package com.example.backend.support;

import com.example.backend.common.type.DocumentStatus;
import com.example.backend.common.type.Engine;
import com.example.backend.common.type.NetworkMode;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.document.entity.Document;
import com.example.backend.ocr.entity.OcrRun;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 테스트 공통 도구: 빈 PDF 생성, id 가 채워진 엔티티 생성, BusinessException 코드 검증. */
public final class TestFixtures {

    private TestFixtures() {
    }

    /** 빈 페이지 n 장짜리 실제 PDF 바이트 */
    public static byte[] pdf(int pages) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Document document(Long id, Long userId, DocumentStatus status) {
        Document d = new Document(userId, "보고서.pdf", "pdfs/" + userId + "/" + id + ".pdf", 1024L);
        d.changeStatus(status);
        ReflectionTestUtils.setField(d, "id", id);
        return d;
    }

    public static OcrRun run(Long id, Long documentId, Engine engine) {
        OcrRun r = new OcrRun(documentId, engine, NetworkMode.OPEN, false);
        ReflectionTestUtils.setField(r, "id", id);
        return r;
    }

    /** BusinessException 이면서 기대한 ErrorCode 인지 확인하고, 예외를 돌려줘 메시지를 추가로 검증할 수 있게 한다. */
    public static BusinessException expectCode(ThrowingCallable call, ErrorCode code) {
        Throwable t = catchThrowable(call);
        assertThat(t).isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) t;
        assertThat(be.getErrorCode()).isEqualTo(code);
        return be;
    }
}
