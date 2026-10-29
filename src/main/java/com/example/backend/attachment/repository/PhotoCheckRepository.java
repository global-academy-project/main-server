package com.example.backend.attachment.repository;

import com.example.backend.attachment.entity.PhotoCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PhotoCheckRepository extends JpaRepository<PhotoCheck, Long> {

    List<PhotoCheck> findByAttachmentIdOrderByIdDesc(Long attachmentId);

    /** 첨부의 최신 확인 결과 */
    Optional<PhotoCheck> findTopByAttachmentIdOrderByIdDesc(Long attachmentId);
}
