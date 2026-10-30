package com.example.backend.corrective.repository;

import com.example.backend.corrective.entity.ActionSource;
import com.example.backend.corrective.entity.ActionStatus;
import com.example.backend.corrective.entity.CorrectiveAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface CorrectiveActionRepository extends JpaRepository<CorrectiveAction, Long> {

    /** 기한 지난 조치: statuses = [OPEN, IN_PROGRESS], date = 오늘 */
    List<CorrectiveAction> findBySiteIdAndStatusInAndDueDateBeforeOrderByDueDateAsc(
            Long siteId, Collection<ActionStatus> statuses, LocalDate date);

    List<CorrectiveAction> findByAssigneeIdAndStatusInOrderByDueDateAsc(Long assigneeId, Collection<ActionStatus> statuses);

    /** 출처 기록의 조치들 (예: 이 아차사고의 개선 조치) */
    List<CorrectiveAction> findBySourceTypeAndSourceIdOrderByIdAsc(ActionSource sourceType, Long sourceId);

    /** 대시보드: 끝나지 않은 조치 (기한 순, 기한 없는 것은 뒤) */
    List<CorrectiveAction> findBySiteIdAndStatusInOrderByDueDateAscIdAsc(Long siteId, Collection<ActionStatus> statuses);
}
