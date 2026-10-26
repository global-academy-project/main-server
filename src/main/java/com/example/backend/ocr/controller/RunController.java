package com.example.backend.ocr.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.document.dto.StoredFile;
import com.example.backend.ocr.dto.ExportFile;
import com.example.backend.ocr.dto.RunDetail;
import com.example.backend.ocr.dto.RunSummary;
import com.example.backend.ocr.service.OcrRunService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/documents/{documentId}/runs")
public class RunController {

    private final OcrRunService runService;

    public RunController(OcrRunService runService) {
        this.runService = runService;
    }

    /** 10. 실행 이력 (최신순) */
    @GetMapping
    public ApiResponse<List<RunSummary>> list(@AuthenticationPrincipal Long userId,
                                              @PathVariable Long documentId) {
        return ApiResponse.ok(runService.list(userId, documentId));
    }

    /** 11. 실행 상세 */
    @GetMapping("/{runId}")
    public ApiResponse<RunDetail> get(@AuthenticationPrincipal Long userId,
                                      @PathVariable Long documentId,
                                      @PathVariable Long runId) {
        return ApiResponse.ok(runService.get(userId, documentId, runId));
    }

    /** 13. 내보내기: 성공 응답은 봉투 없이 파일 본문 */
    @GetMapping("/{runId}/export")
    public ResponseEntity<Object> export(@AuthenticationPrincipal Long userId,
                                         @PathVariable Long documentId,
                                         @PathVariable Long runId,
                                         @RequestParam String format) {
        ExportFile file = runService.export(userId, documentId, runId, format);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(file.mediaType())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(file.body());
    }

    /** 14. 추출 이미지 (PNG) */
    @GetMapping("/{runId}/images/{imageId}")
    public ResponseEntity<Resource> image(@AuthenticationPrincipal Long userId,
                                          @PathVariable Long documentId,
                                          @PathVariable Long runId,
                                          @PathVariable Long imageId) {
        StoredFile stored = runService.getImage(userId, documentId, runId, imageId);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(new FileSystemResource(stored.path()));
    }
}
