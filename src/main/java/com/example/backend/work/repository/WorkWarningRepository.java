package com.example.backend.work.repository;

import com.example.backend.work.entity.WorkWarning;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkWarningRepository extends JpaRepository<WorkWarning, Long> {

    List<WorkWarning> findByWorkIdOrderByShownAtAscIdAsc(Long workId);

    /** 아직 확인하지 않은 경고 (승인 요청 전에 확인 필요) */
    List<WorkWarning> findByWorkIdAndAcknowledgedAtIsNullOrderByIdAsc(Long workId);

    /** 같은 아차사고로 이미 경고했나 (DB uk_ww_nm) */
    boolean existsByWorkIdAndNearMissId(Long workId, Long nearMissId);
}
