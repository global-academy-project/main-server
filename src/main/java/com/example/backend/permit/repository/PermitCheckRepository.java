package com.example.backend.permit.repository;

import com.example.backend.permit.entity.PermitCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PermitCheckRepository extends JpaRepository<PermitCheck, Long> {

    List<PermitCheck> findByPermitIdOrderByIdAsc(Long permitId);

    Optional<PermitCheck> findByPermitIdAndItemCode(Long permitId, String itemCode);
}
