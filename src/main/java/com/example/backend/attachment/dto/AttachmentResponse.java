package com.example.backend.attachment.dto;

import com.example.backend.attachment.entity.AttachmentKind;
import com.example.backend.attachment.entity.AttachmentOwner;

import java.time.LocalDateTime;

/**
 * 첨부 한 건.
 *  - url: 내려받기 경로 (GET, 로그인 토큰 필요). 화면은 이 경로로 받아 보여 준다
 *  - takenAt: 사진 파일 안의 촬영 시각 (JPEG EXIF). 없으면 null (메신저를 거치면 지워짐). 확정값이 아니다
 *  - duplicateOfId: 같은 현장에 똑같은 파일(SHA-256)이 이미 있으면 그 첨부 id (사진 재사용 의심). 저장은 막지 않는다
 */
public record AttachmentResponse(Long attachmentId, Long siteId, AttachmentOwner ownerType, Long ownerId,
                                 AttachmentKind kind, String fileName, String mimeType, Long fileSize,
                                 LocalDateTime takenAt, Long uploadedBy, String uploadedByName, LocalDateTime createdAt,
                                 String url, Long duplicateOfId) {
}
