package com.example.backend.notification.repository;

import com.example.backend.notification.entity.NotificationRecipient;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationRecipientRepository extends JpaRepository<NotificationRecipient, Long> {

    /** 화면 목록: 끄지 않은 것 최신순 (만료 거르기는 서비스에서 알림과 같이) */
    List<NotificationRecipient> findByUserIdAndDismissedAtIsNullOrderByIdDesc(Long userId, Pageable pageable);

    /** 배지 숫자 */
    long countByUserIdAndReadAtIsNullAndDismissedAtIsNull(Long userId);

    /** SSE 재접속: 이 id 이후 못 받은 것 */
    List<NotificationRecipient> findByUserIdAndIdGreaterThanAndDeliveredAtIsNullOrderByIdAsc(Long userId, Long afterId);

    /** 모두 읽음 */
    @Modifying
    @Query("""
            update NotificationRecipient r set r.readAt = :now
             where r.userId = :userId and r.readAt is null and r.createdAt <= :now
            """)
    int markAllRead(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /** 정리 배치: 끈 지 오래된 행 삭제 (notifications 는 남김) */
    @Modifying
    @Query("delete from NotificationRecipient r where r.dismissedAt < :before")
    int deleteDismissedBefore(@Param("before") LocalDateTime before);
}
