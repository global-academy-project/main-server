package com.example.backend.config;

import com.example.backend.auth.internal.InternalTokenFilter;
import com.example.backend.auth.jwt.AccountStateFilter;
import com.example.backend.auth.jwt.JwtAuthenticationEntryPoint;
import com.example.backend.auth.jwt.JwtAuthenticationFilter;
import com.example.backend.auth.jwt.JwtProvider;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.common.response.ErrorResponseWriter;
import com.example.backend.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 보안 체인 2개.
 *  1) 내부 API(/internal/**): AI 서버 전용. X-Internal-Token 만 받고 JWT 는 보지 않는다
 *  2) 그 밖의 모든 요청: JWT + 계정 상태
 * 체인 선택은 컨트롤러 매핑과 같은 (디코딩된) 경로로 판단하므로, 인코딩된 경로로 토큰 검사를 건너뛸 수 없다.
 */
@Configuration
public class SecurityConfig {

    private static final String INTERNAL = "/internal/**";

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 폼 로그인·기본 인증은 쓰지 않는다 (JWT·내부 토큰만). 이 빈이 있으면 스프링 부트가
     * 임시 비밀번호 사용자(Using generated security password)를 만들지 않는다.
     */
    @Bean
    UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("폼 로그인은 쓰지 않습니다.");
        };
    }

    @Bean
    @Order(1)
    SecurityFilterChain internalFilterChain(HttpSecurity http,
                                            @Value("${app.internal.token}") String internalToken) throws Exception {
        http.securityMatcher(INTERNAL)
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().hasRole(InternalTokenFilter.ROLE))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) ->
                                ErrorResponseWriter.write(response, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, ex) ->
                                ErrorResponseWriter.write(response, ErrorCode.UNAUTHORIZED)))
                .addFilterBefore(new InternalTokenFilter(internalToken), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain filterChain(HttpSecurity http, JwtProvider jwtProvider,
                                    UserRepository userRepository) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        // 내부 API 는 위 체인이 받는다. 여기까지 왔다면 막는다 (웹 JWT 로는 내부 API 불가)
                        .requestMatchers(INTERNAL).denyAll()
                        // 비밀번호 변경은 /auth 아래지만 로그인 필요 (만료 토큰이면 TOKEN_EXPIRED 로 응답되게)
                        .requestMatchers("/api/v1/auth/password/change").authenticated()
                        .requestMatchers("/api/v1/auth/**", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new JwtAuthenticationEntryPoint())
                        // 로그인은 했지만 권한이 없음 (일반 회원이 관리자 API 호출) → 403 FORBIDDEN
                        .accessDeniedHandler((request, response, ex) ->
                                ErrorResponseWriter.write(response, ErrorCode.FORBIDDEN)))
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new AccountStateFilter(userRepository), JwtAuthenticationFilter.class);
        return http.build();
    }
}
