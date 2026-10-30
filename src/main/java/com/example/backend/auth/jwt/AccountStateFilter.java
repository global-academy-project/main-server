package com.example.backend.auth.jwt;

import com.example.backend.common.exception.ErrorCode;
import com.example.backend.common.response.ErrorResponseWriter;
import com.example.backend.user.entity.User;
import com.example.backend.user.entity.UserStatus;
import com.example.backend.user.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * JWT 검증 다음에 계정 상태를 매 요청 확인한다 (access token 이 살아 있어도 바로 반영되게).
 *  - 계정이 없거나 ACTIVE 가 아니면 인증을 지운다 → 보호된 API는 401 (잠금·중지 코드로 응답)
 *  - 비밀번호 변경이 필요한 계정은 아래 경로만 허용하고 나머지는 403 PASSWORD_CHANGE_REQUIRED
 *  - 권한(ROLE_ADMIN / ROLE_MEMBER)을 채운다 → SecurityConfig 에서 /api/v1/admin/** 를 ADMIN 만 허용
 * 사용자 수가 적은 설치형이라 요청마다 users 한 행을 읽는 비용은 문제 되지 않는다.
 */
public class AccountStateFilter extends OncePerRequestFilter {

    /** 비밀번호 강제 변경 중에도 쓸 수 있는 경로 (/api/v1/auth/** 는 원래 열려 있음) */
    private static final Set<String> ALLOWED_WHILE_MUST_CHANGE = Set.of("/api/v1/users/me");

    private final UserRepository userRepository;

    public AccountStateFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long userId) {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null || user.getStatus() != UserStatus.ACTIVE) {
                SecurityContextHolder.clearContext();
                request.setAttribute(JwtAuthenticationFilter.ERROR_ATTRIBUTE, codeFor(user));
            } else if (user.isMustChangePassword() && !isAllowedWhileMustChange(request)) {
                ErrorResponseWriter.write(response, ErrorCode.PASSWORD_CHANGE_REQUIRED);
                return;
            } else {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        userId, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))));
            }
        }
        chain.doFilter(request, response);
    }

    private static ErrorCode codeFor(User user) {
        if (user == null) return ErrorCode.UNAUTHORIZED;
        return switch (user.getStatus()) {
            case LOCKED -> ErrorCode.ACCOUNT_LOCKED;
            case INACTIVE -> ErrorCode.ACCOUNT_INACTIVE;
            default -> ErrorCode.UNAUTHORIZED;
        };
    }

    private static boolean isAllowedWhileMustChange(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/v1/auth/") || ALLOWED_WHILE_MUST_CHANGE.contains(path);
    }
}
