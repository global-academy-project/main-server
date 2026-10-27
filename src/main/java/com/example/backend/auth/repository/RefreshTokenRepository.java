package com.example.backend.auth.repository;

import com.example.backend.auth.entity.RefreshToken;
import com.example.backend.auth.entity.RevokeReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** 한 사용자의 살아 있는 세션을 모두 끊는다 (비밀번호 변경, 탈취 의심, 관리자 조치) */
    // clearAutomatically 는 쓰지 않는다: 같은 트랜잭션에서 읽은 User 변경이 날아갈 수 있다
    @Modifying(flushAutomatically = true)
    @Query("""
            update RefreshToken t set t.revokedAt = :now, t.revokeReason = :reason
            where t.userId = :userId and t.revokedAt is null
            """)
    int revokeAllActive(@Param("userId") Long userId, @Param("reason") RevokeReason reason,
                        @Param("now") LocalDateTime now);
}
