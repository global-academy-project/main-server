package com.example.backend.approval.repository;

import com.example.backend.approval.entity.Approval;
import com.example.backend.approval.entity.ApprovalStatus;
import com.example.backend.approval.entity.ApprovalTarget;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ApprovalRepository extends JpaRepository<Approval, Long> {

    /** 그 기록의 대기 중 결재 (하나뿐, V6 uk_appr_pending) */
    Optional<Approval> findByTargetTypeAndTargetIdAndStatus(ApprovalTarget targetType, Long targetId,
                                                            ApprovalStatus status);

    /** 그 기록의 결재 이력 (최근 순) */
    List<Approval> findByTargetTypeAndTargetIdOrderByRequestedAtDescIdDesc(ApprovalTarget targetType, Long targetId);

    /** 현장 결재함 */
    Page<Approval> findBySiteIdAndStatusOrderByRequestedAtAscIdAsc(Long siteId, ApprovalStatus status,
                                                                  Pageable pageable);

    /** 내가 요청한 문서 (최근 순) */
    Page<Approval> findBySiteIdAndRequestedByOrderByRequestedAtDescIdDesc(Long siteId, Long requestedBy, Pageable pageable);

    /** 결정할 때 같은 결재를 두 사람이 동시에 처리하지 않게 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Approval a where a.id = :id")
    Optional<Approval> findForUpdate(@Param("id") Long id);
}
