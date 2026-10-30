package com.example.backend.attachment.repository;

import com.example.backend.attachment.entity.Attachment;
import com.example.backend.attachment.entity.AttachmentKind;
import com.example.backend.attachment.entity.AttachmentOwner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findByOwnerTypeAndOwnerIdOrderByIdAsc(AttachmentOwner ownerType, Long ownerId);

    /** 같은 현장에 같은 해시의 이전 첨부 (사진 재사용 의심). 자기 자신은 뺀다 */
    Optional<Attachment> findFirstBySiteIdAndSha256AndIdNotOrderByIdAsc(Long siteId, String sha256, Long excludeId);

    Optional<Attachment> findByIdAndSiteId(Long id, Long siteId);

    /** 대시보드: TBM 사진이 있는가 */
    boolean existsByOwnerTypeAndOwnerIdAndKind(AttachmentOwner ownerType, Long ownerId, AttachmentKind kind);
}
