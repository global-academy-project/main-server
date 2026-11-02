package com.example.backend.worker.repository;

import com.example.backend.worker.entity.Team;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TeamRepository extends JpaRepository<Team, Long> {

    List<Team> findByActiveTrueOrderByNameAsc();

    boolean existsByName(String name);

    boolean existsByBusinessNo(String businessNo);

    /** 관리 화면: 사용 중지 팀 포함 */
    List<Team> findAllByOrderByNameAsc();
}
