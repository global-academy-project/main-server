package com.example.backend.ocr.repository;

import com.example.backend.ocr.entity.OcrImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OcrImageRepository extends JpaRepository<OcrImage, Long> {

    List<OcrImage> findByRunIdOrderByPageNo(Long runId);

    Optional<OcrImage> findByIdAndRunId(Long id, Long runId);

    void deleteByRunId(Long runId);
}
