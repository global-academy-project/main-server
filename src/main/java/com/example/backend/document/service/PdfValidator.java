package com.example.backend.document.service;

import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 업로드 파일 검증: 비어있음 / 크기 / 확장자 / PDF 시그니처 / 페이지 수. 통과하면 페이지 수를 반환. */
@Component
public class PdfValidator {

    public static final long MAX_SIZE = 20L * 1024 * 1024;
    public static final int MAX_PAGES = 50;

    public int validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "파일이 비어 있습니다.");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "파일을 읽을 수 없습니다.");
        }
        // 확장자만 pdf 로 바꾼 파일을 거르기 위해 매직 넘버도 확인
        if (bytes.length < 5 || !"%PDF-".equals(new String(bytes, 0, 5, StandardCharsets.US_ASCII))) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        }

        int pages;
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            pages = doc.getNumberOfPages();
        } catch (IOException e) {
            // 손상되었거나 암호가 걸린 PDF
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "열 수 없는 PDF 파일입니다.");
        }
        if (pages < 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "페이지가 없는 PDF 입니다.");
        }
        if (pages > MAX_PAGES) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "PDF는 최대 " + MAX_PAGES + "페이지까지 업로드할 수 있습니다.");
        }
        return pages;
    }
}
