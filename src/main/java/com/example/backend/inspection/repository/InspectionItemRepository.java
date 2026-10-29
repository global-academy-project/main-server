package com.example.backend.inspection.repository;

import com.example.backend.inspection.entity.InspectionItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InspectionItemRepository extends JpaRepository<InspectionItem, Long> {

    List<InspectionItem> findByInspectionIdOrderBySeqAsc(Long inspectionId);
}
