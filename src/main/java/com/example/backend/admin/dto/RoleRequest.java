package com.example.backend.admin.dto;

import com.example.backend.user.entity.UserRole;
import jakarta.validation.constraints.NotNull;

/** PUT /api/v1/admin/users/{id}/role */
public record RoleRequest(
        @NotNull UserRole role
) {
}
