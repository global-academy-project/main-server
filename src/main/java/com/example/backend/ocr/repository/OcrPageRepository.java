package com.example.backend.ocr.repository;

import com.example.backend.ocr.entity.OcrPage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OcrPageRepository extends JpaRepository<OcrPage, Long> {

    List<OcrPage> findByRunIdOrderByPageNo(Long runId);

    void deleteByRunId(Long runId);
}
