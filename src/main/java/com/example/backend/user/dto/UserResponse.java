package com.example.backend.user.dto;

import com.example.backend.user.entity.User;

public record UserResponse(Long userId, String email, String name) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getName());
    }
}
