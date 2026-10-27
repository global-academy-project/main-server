package com.example.backend.auth.repository;

import com.example.backend.auth.entity.PasswordHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PasswordHistoryRepository extends JpaRepository<PasswordHistory, Long> {

    /** 최근 것부터. 개수는 Pageable 로 제한 (PageRequest.of(0, n)) */
    List<PasswordHistory> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);
}
