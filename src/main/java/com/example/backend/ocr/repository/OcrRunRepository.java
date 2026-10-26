package com.example.backend.ocr.repository;

import com.example.backend.common.type.RunStatus;
import com.example.backend.ocr.entity.OcrRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OcrRunRepository extends JpaRepository<OcrRun, Long> {

    Optional<OcrRun> findByIdAndDocumentId(Long id, Long documentId);

    List<OcrRun> findByDocumentIdOrderByCreatedAtDesc(Long documentId);

    Optional<OcrRun> findFirstByDocumentIdAndStatusOrderByCreatedAtDesc(Long documentId, RunStatus status);

    /** 문서의 가장 최근 run (id 는 증가하므로 id 내림차순 첫 건이 최신) */
    Optional<OcrRun> findFirstByDocumentIdOrderByIdDesc(Long documentId);

    /** 목록 화면용: 여러 문서의 run 을 한 번에 조회 (N+1 방지) */
    List<OcrRun> findByDocumentIdIn(Collection<Long> documentIds);

    List<OcrRun> findByStatus(RunStatus status);
}
