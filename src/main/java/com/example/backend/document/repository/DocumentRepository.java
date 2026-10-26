package com.example.backend.document.repository;

import com.example.backend.common.type.DocumentStatus;
import com.example.backend.document.entity.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    /** 본인 문서만 조회. 타인 문서는 존재하지 않는 것처럼 empty. */
    Optional<Document> findByIdAndUserId(Long id, Long userId);

    Page<Document> findByUserId(Long userId, Pageable pageable);

    Page<Document> findByUserIdAndStatus(Long userId, DocumentStatus status, Pageable pageable);

    List<Document> findByStatus(DocumentStatus status);
}
