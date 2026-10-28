package com.example.backend.user.repository;

import com.example.backend.user.entity.User;
import com.example.backend.user.entity.UserRole;
import com.example.backend.user.entity.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByRole(UserRole role);

    long countByRoleAndStatus(UserRole role, UserStatus status);

    List<User> findAllByOrderByCreatedAtDesc();

    List<User> findByStatusOrderByCreatedAtDesc(UserStatus status);
}
