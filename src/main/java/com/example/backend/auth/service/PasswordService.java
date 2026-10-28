package com.example.backend.auth.service;

import com.example.backend.auth.entity.PasswordHistory;
import com.example.backend.auth.entity.RevokeReason;
import com.example.backend.auth.repository.PasswordHistoryRepository;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.user.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 비밀번호 바꾸기 공통 규칙 (재설정·내 정보 변경·강제 변경이 모두 여기를 쓴다).
 *  1) 지금 비밀번호 + 최근 app.auth.password-history-count 개(기본 3)와 같으면 PASSWORD_REUSED
 *  2) 지금 해시를 이력에 남기고 새 해시로 변경 (실패 횟수 초기화, 잠금 해제, 강제 변경 해제)
 *  3) 그 사용자의 로그인 세션을 모두 끊는다 (다른 기기 포함)
 */
@Service
// 여기서 던진 BusinessException 이 트랜잭션을 rollback-only 로 만들지 않게 한다 (롤백 여부는 호출한 쪽이 정함)
@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = BusinessException.class)
public class PasswordService {

    private final PasswordHistoryRepository historyRepository;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final int historyCount;

    public PasswordService(PasswordHistoryRepository historyRepository, RefreshTokenService refreshTokenService,
                           PasswordEncoder passwordEncoder, Clock clock,
                           @Value("${app.auth.password-history-count}") int historyCount) {
        this.historyRepository = historyRepository;
        this.refreshTokenService = refreshTokenService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.historyCount = historyCount;
    }

    public void change(User user, String newRawPassword) {
        if (isReused(user, newRawPassword)) {
            throw new BusinessException(ErrorCode.PASSWORD_REUSED);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        historyRepository.save(new PasswordHistory(user.getId(), user.getPasswordHash(), now));
        user.changePassword(passwordEncoder.encode(newRawPassword), now);
        refreshTokenService.revokeAll(user.getId(), RevokeReason.PASSWORD_CHANGED);
    }

    /**
     * 관리자가 임시 비밀번호 발급. 재사용 검사는 하지 않는다 (임시값이라). 다음 로그인 때 변경을 강제하고 세션을 모두 끊는다.
     */
    public void assignTemporary(User user, String temporaryRawPassword) {
        LocalDateTime now = LocalDateTime.now(clock);
        historyRepository.save(new PasswordHistory(user.getId(), user.getPasswordHash(), now));
        user.assignTemporaryPassword(passwordEncoder.encode(temporaryRawPassword), now);
        refreshTokenService.revokeAll(user.getId(), RevokeReason.ADMIN);
    }

    /** 현재 비밀번호 확인 (내 정보에서 비밀번호·이메일을 바꿀 때) */
    public void checkCurrent(User user, String currentRawPassword) {
        if (currentRawPassword == null || !passwordEncoder.matches(currentRawPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PASSWORD_MISMATCH);
        }
    }

    private boolean isReused(User user, String newRawPassword) {
        if (passwordEncoder.matches(newRawPassword, user.getPasswordHash())) {
            return true;
        }
        return historyRepository
                .findByUserIdOrderByCreatedAtDescIdDesc(user.getId(), PageRequest.of(0, historyCount))
                .stream()
                .anyMatch(h -> passwordEncoder.matches(newRawPassword, h.getPasswordHash()));
    }
}
