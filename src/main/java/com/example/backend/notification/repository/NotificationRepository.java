package com.example.backend.notification.repository;

import com.example.backend.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 같은 알림이 이미 있나 (마감 알림 등 중복 방지) */
    Optional<Notification> findByDedupeKey(String dedupeKey);
}
