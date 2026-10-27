package com.example.backend.auth.repository;

import com.example.backend.auth.entity.AccountToken;
import com.example.backend.auth.entity.TokenPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface AccountTokenRepository extends JpaRepository<AccountToken, Long> {

    Optional<AccountToken> findByTokenHash(String tokenHash);

    /** 같은 사용자·용도의 살아 있는 토큰을 폐기한다. 새 토큰 INSERT 전에 같은 트랜잭션에서 호출 (uk_at_active) */
    @Modifying(flushAutomatically = true)
    @Query("""
            update AccountToken t set t.revokedAt = :now
            where t.userId = :userId and t.purpose = :purpose
              and t.usedAt is null and t.revokedAt is null
            """)
    int revokeActive(@Param("userId") Long userId, @Param("purpose") TokenPurpose purpose,
                     @Param("now") LocalDateTime now);

    /** 같은 IP의 최근 요청 수 (메일 폭탄 방지) */
    long countByRequestedIpAndCreatedAtAfter(String requestedIp, LocalDateTime since);

    /** 같은 사용자의 최근 요청 수 (IP를 바꿔 가며 한 사람에게 메일을 퍼붓는 것 방지) */
    long countByUserIdAndPurposeAndCreatedAtAfter(Long userId, TokenPurpose purpose, LocalDateTime since);
}
