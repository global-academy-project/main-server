package com.example.backend.work.repository;

import com.example.backend.work.entity.WorkMaterial;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkMaterialRepository extends JpaRepository<WorkMaterial, Long> {

    List<WorkMaterial> findByWorkIdOrderByIdAsc(Long workId);

    boolean existsByWorkIdAndName(Long workId, String name);
}
