package com.example.backend.user.dto;

import com.example.backend.user.entity.User;
import com.example.backend.user.entity.UserRole;
import com.example.backend.user.entity.UserStatus;

/**
 * 사용자 정보 응답. 현장 목록(sites)·lastSiteId·lockVersion 은 3단계(내 정보)에서 추가한다.
 */
public record UserResponse(
        Long userId,
        String email,
        String name,
        UserRole role,
        UserStatus status,
        String phone,
        String department,
        String jobTitle,
        boolean emailVerified,
        boolean mustChangePassword
) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(), user.getEmail(), user.getName(),
                user.getRole(), user.getStatus(),
                user.getPhone(), user.getDepartment(), user.getJobTitle(),
                user.isEmailVerified(), user.isMustChangePassword());
    }
}
