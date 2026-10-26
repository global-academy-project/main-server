package com.example.backend.document.controller;

import com.example.backend.common.response.ApiResponse;
import com.example.backend.common.type.DocumentStatus;
import com.example.backend.common.type.Engine;
import com.example.backend.document.dto.DocumentDetailResponse;
import com.example.backend.document.dto.DocumentPageResponse;
import com.example.backend.document.dto.DocumentUploadResponse;
import com.example.backend.document.dto.ReprocessRequest;
import com.example.backend.document.dto.StoredFile;
import com.example.backend.document.service.DocumentService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /** 5. 업로드 -> 202. OCR 은 비동기로 진행되므로 GET /{id} 로 상태를 폴링한다. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DocumentUploadResponse>> upload(
            @AuthenticationPrincipal Long userId,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(defaultValue = "AUTO") Engine engine,
            @RequestParam(defaultValue = "false") boolean extractImages) {
        DocumentUploadResponse body = documentService.upload(userId, file, engine, extractImages);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(body));
    }

    /** 6. 목록 */
    @GetMapping
    public ApiResponse<DocumentPageResponse> list(
            @AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) DocumentStatus status,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return ApiResponse.ok(documentService.list(userId, page, size, status, sort));
    }

    /** 7. 상세 */
    @GetMapping("/{id}")
    public ApiResponse<DocumentDetailResponse> get(@AuthenticationPrincipal Long userId,
                                                   @PathVariable Long id) {
        return ApiResponse.ok(documentService.get(userId, id));
    }

    /** 8. 삭제 */
    @DeleteMapping("/{id}")
    public ApiResponse<Object> delete(@AuthenticationPrincipal Long userId,
                                      @PathVariable Long id) {
        documentService.delete(userId, id);
        return ApiResponse.okEmpty();
    }

    /** 9. 원본 PDF (inline) */
    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> file(@AuthenticationPrincipal Long userId,
                                         @PathVariable Long id) {
        StoredFile stored = documentService.getFile(userId, id);
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(stored.originalName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new FileSystemResource(stored.path()));
    }

    /** 12. 재처리 -> 202, 새 run 추가 */
    @PostMapping("/{id}/reprocess")
    public ResponseEntity<ApiResponse<DocumentUploadResponse>> reprocess(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id,
            @RequestBody ReprocessRequest request) {
        DocumentUploadResponse body = documentService.reprocess(userId, id, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(body));
    }
}
