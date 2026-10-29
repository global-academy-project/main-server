package com.example.backend.dailylog.repository;

import com.example.backend.dailylog.entity.DailySafetyLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailySafetyLogRepository extends JpaRepository<DailySafetyLog, Long> {

    Optional<DailySafetyLog> findBySiteIdAndLogDate(Long siteId, LocalDate logDate);

    List<DailySafetyLog> findBySiteIdAndLogDateBetweenOrderByLogDateDesc(Long siteId, LocalDate from, LocalDate to);
}
