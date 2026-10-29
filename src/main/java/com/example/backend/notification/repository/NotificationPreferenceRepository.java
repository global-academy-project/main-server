package com.example.backend.notification.repository;

import com.example.backend.notification.entity.NotificationPreference;
import com.example.backend.notification.entity.NotificationPreferenceId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationPreferenceRepository
        extends JpaRepository<NotificationPreference, NotificationPreferenceId> {

    /** 행이 없는 종류는 기본값(웹 켜짐, 문자 꺼짐) */
    List<NotificationPreference> findByUserId(Long userId);
}
