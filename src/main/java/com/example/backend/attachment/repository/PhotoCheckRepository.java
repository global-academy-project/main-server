package com.example.backend.attachment.repository;

import com.example.backend.attachment.entity.PhotoCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PhotoCheckRepository extends JpaRepository<PhotoCheck, Long> {

    List<PhotoCheck> findByAttachmentIdOrderByIdDesc(Long attachmentId);

    /** 첨부의 최신 확인 결과 */
    Optional<PhotoCheck> findTopByAttachmentIdOrderByIdDesc(Long attachmentId);

    /** 다른 사진의 확인 결과가 이 첨부를 "똑같은 파일"로 가리키는가 */
    boolean existsByDuplicateOfAndAttachmentIdNot(Long duplicateOf, Long attachmentId);

    /** 사진을 지울 때 그 사진의 확인 결과도 지운다 (작성 중인 기록만) */
    void deleteByAttachmentId(Long attachmentId);
}
