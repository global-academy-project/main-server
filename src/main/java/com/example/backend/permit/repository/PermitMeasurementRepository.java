package com.example.backend.permit.repository;

import com.example.backend.permit.entity.MeasurementItem;
import com.example.backend.permit.entity.PermitMeasurement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PermitMeasurementRepository extends JpaRepository<PermitMeasurement, Long> {

    /** 측정 시각 순 (같은 시각이면 입력 순) */
    List<PermitMeasurement> findByPermitIdAndPhaseOrderByMeasuredAtAscIdAsc(Long permitId, String phase);

    boolean existsByPermitIdAndPhaseAndItem(Long permitId, String phase, MeasurementItem item);
}
