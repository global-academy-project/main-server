package com.example.backend.attachment.controller;

import com.example.backend.attachment.dto.AttachmentResponse;
import com.example.backend.attachment.entity.AttachmentOwner;
import com.example.backend.attachment.service.AttachmentService;
import com.example.backend.common.response.ApiResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 사진 첨부. 공통 경로(ownerType·ownerId)와 TBM 전용 경로(D 안전일지 요청서 7번)를 같이 둔다.
 *  - POST /api/v1/attachments                (multipart: ownerType, ownerId, file)
 *  - GET  /api/v1/attachments?ownerType=&ownerId=
 *  - POST /api/v1/tbm/{tbmId}/photos           (multipart: file)
 *  - GET  /api/v1/tbm/{tbmId}/photos
 *  - GET  /api/v1/attachments/{id}/file        내려받기 (현장 권한)
 *  - DELETE /api/v1/attachments/{id}           잘못 올린 사진 지우기
 */
@RestController
@RequestMapping("/api/v1")
public class AttachmentController {

    private final AttachmentService service;

    public AttachmentController(AttachmentService service) {
        this.service = service;
    }

    @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AttachmentResponse> upload(@AuthenticationPrincipal Long userId,
                                                  @RequestParam AttachmentOwner ownerType, @RequestParam Long ownerId,
                                                  @RequestParam(value = "file", required = false) MultipartFile file) {
        return ApiResponse.ok(service.upload(userId, ownerType, ownerId, file));
    }

    @GetMapping("/attachments")
    public ApiResponse<List<AttachmentResponse>> list(@AuthenticationPrincipal Long userId,
                                                      @RequestParam AttachmentOwner ownerType, @RequestParam Long ownerId) {
        return ApiResponse.ok(service.list(userId, ownerType, ownerId));
    }

    @PostMapping(value = "/tbm/{tbmId}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AttachmentResponse> uploadTbmPhoto(@AuthenticationPrincipal Long userId, @PathVariable Long tbmId,
                                                          @RequestParam(value = "file", required = false) MultipartFile file) {
        return ApiResponse.ok(service.upload(userId, AttachmentOwner.TBM, tbmId, file));
    }

    @GetMapping("/tbm/{tbmId}/photos")
    public ApiResponse<List<AttachmentResponse>> tbmPhotos(@AuthenticationPrincipal Long userId, @PathVariable Long tbmId) {
        return ApiResponse.ok(service.list(userId, AttachmentOwner.TBM, tbmId));
    }

    @GetMapping("/attachments/{attachmentId}/file")
    public ResponseEntity<Resource> file(@AuthenticationPrincipal Long userId, @PathVariable Long attachmentId) {
        AttachmentService.FileDownload f = service.file(userId, attachmentId);
        ResponseEntity.BodyBuilder res = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(f.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300");
        if (f.size() >= 0) {
            res.contentLength(f.size());
        }
        return res.body(new FileSystemResource(f.path()));
    }

    @DeleteMapping("/attachments/{attachmentId}")
    public ApiResponse<Object> delete(@AuthenticationPrincipal Long userId, @PathVariable Long attachmentId) {
        service.delete(userId, attachmentId);
        return ApiResponse.okEmpty();
    }
}
