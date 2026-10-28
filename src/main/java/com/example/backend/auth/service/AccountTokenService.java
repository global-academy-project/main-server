package com.example.backend.auth.service;

import com.example.backend.auth.entity.AccountToken;
import com.example.backend.auth.entity.TokenPurpose;
import com.example.backend.auth.repository.AccountTokenRepository;
import com.example.backend.auth.token.TokenCodec;
import com.example.backend.common.exception.BusinessException;
import com.example.backend.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 메일 링크 토큰 발급·사용. 호출하는 쪽 트랜잭션 안에서 동작한다.
 * 유효 시간은 app.auth.mail-token-minutes (기본 10분). DB 상한(재설정 1시간, 인증 72시간)을 넘기면 INSERT가 거부된다.
 *
 * 요청 제한 (메일 폭탄 방지)
 *  - 사용자·용도당 1시간에 app.auth.mail-max-per-user-hour 회 (기본 5)
 *  - 같은 IP 1시간에 app.auth.mail-max-per-ip-hour 회 (기본 30). 회사 안에서는 여러 사람이 같은 IP를 쓰므로 넉넉하게
 */
@Service
// 여기서 던진 BusinessException 이 트랜잭션을 rollback-only 로 만들지 않게 한다 (롤백 여부는 호출한 쪽이 정함)
@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = BusinessException.class)
public class AccountTokenService {

    /** 발급 결과: 메일에 넣을 원문과 유효 시간 */
    public record Issued(String rawToken, long validMinutes) {
    }

    private final AccountTokenRepository repository;
    private final Clock clock;
    private final long validMinutes;
    private final int maxPerUserHour;
    private final int maxPerIpHour;

    public AccountTokenService(AccountTokenRepository repository, Clock clock,
                               @Value("${app.auth.mail-token-minutes}") long validMinutes,
                               @Value("${app.auth.mail-max-per-user-hour}") int maxPerUserHour,
                               @Value("${app.auth.mail-max-per-ip-hour}") int maxPerIpHour) {
        this.repository = repository;
        this.clock = clock;
        this.validMinutes = validMinutes;
        this.maxPerUserHour = maxPerUserHour;
        this.maxPerIpHour = maxPerIpHour;
    }

    /** 같은 IP 요청 제한. 계정이 있든 없든 똑같이 적용해야 가입 여부가 드러나지 않는다 */
    public void checkIpLimit(String requestedIp) {
        if (requestedIp == null) return;
        LocalDateTime hourAgo = LocalDateTime.now(clock).minusHours(1);
        if (repository.countByRequestedIpAndCreatedAtAfter(requestedIp, hourAgo) >= maxPerIpHour) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }
    }

    /**
     * 새 토큰 발급. 같은 사용자·용도의 이전 토큰은 폐기한다 (살아 있는 토큰은 하나).
     * 사용자별 한도를 넘었으면 발급하지 않고 빈 값을 돌려준다 (호출한 쪽은 조용히 넘어간다).
     */
    public Optional<Issued> issue(Long userId, TokenPurpose purpose, String targetEmail, String requestedIp) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (repository.countByUserIdAndPurposeAndCreatedAtAfter(userId, purpose, now.minusHours(1)) >= maxPerUserHour) {
            return Optional.empty();
        }
        repository.revokeActive(userId, purpose, now);
        String raw = TokenCodec.newRawToken();
        repository.save(new AccountToken(userId, purpose, TokenCodec.sha256Hex(raw), targetEmail,
                requestedIp, now, now.plusMinutes(validMinutes)));
        return Optional.of(new Issued(raw, validMinutes));
    }

    /**
     * 토큰 사용. 유효하면 사용 처리하고 돌려준다.
     * 없음·용도 다름 → TOKEN_INVALID, 이미 사용 → TOKEN_ALREADY_USED,
     * 만료 또는 재발송으로 폐기된 옛 링크 → TOKEN_EXPIRED_LINK
     */
    public AccountToken consume(String rawToken, TokenPurpose purpose) {
        LocalDateTime now = LocalDateTime.now(clock);
        AccountToken token = repository.findByTokenHash(TokenCodec.sha256Hex(rawToken))
                .filter(t -> t.getPurpose() == purpose)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID));
        if (token.isUsed()) {
            throw new BusinessException(ErrorCode.TOKEN_ALREADY_USED);
        }
        if (token.isRevoked() || token.isExpired(now)) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED_LINK);
        }
        token.markUsed(now);
        return token;
    }
}
