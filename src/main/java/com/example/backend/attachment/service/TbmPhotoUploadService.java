package com.example.backend.attachment.service;

import com.example.backend.attachment.dto.AttachmentResponse;
import com.example.backend.attachment.entity.AttachmentOwner;
import com.example.backend.attachment.vision.PhotoVision;
import com.example.backend.attachment.vision.PhotoVisionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Optional;

/**
 * 사진 올리기 + TBM 사진 인식. 저장(트랜잭션)을 먼저 끝내고, 인식(최대 app.photo.vision.timeout-seconds)은
 * DB 트랜잭션 밖에서 부른 뒤 결과만 짧은 트랜잭션으로 남긴다. 인식이 실패해도 올리기는 성공이다.
 */
@Service
public class TbmPhotoUploadService {

    private static final Logger log = LoggerFactory.getLogger(TbmPhotoUploadService.class);

    private final AttachmentService attachmentService;
    private final PhotoVisionClient visionClient;

    public TbmPhotoUploadService(AttachmentService attachmentService, PhotoVisionClient visionClient) {
        this.attachmentService = attachmentService;
        this.visionClient = visionClient;
    }

    public AttachmentResponse upload(Long userId, AttachmentOwner ownerType, Long ownerId, MultipartFile file) {
        AttachmentResponse saved = attachmentService.upload(userId, ownerType, ownerId, file);
        if (ownerType != AttachmentOwner.TBM || !visionClient.enabled()) {
            return saved;
        }
        Optional<PhotoVision> vision;
        try {
            vision = visionClient.detect(file.getBytes(), saved.mimeType(), saved.fileName());
        } catch (IOException | RuntimeException e) {
            log.warn("사진 인식을 부르지 못했습니다. attachmentId={} ({})", saved.attachmentId(), e.getClass().getSimpleName());
            return saved;
        }
        return vision.map(v -> attachmentService.applyVision(saved.attachmentId(), v)).orElse(saved);
    }
}