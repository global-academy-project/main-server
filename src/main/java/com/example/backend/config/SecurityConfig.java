package com.example.backend.config;

import com.example.backend.auth.jwt.AccountStateFilter;
import com.example.backend.auth.jwt.JwtAuthenticationEntryPoint;
import com.example.backend.auth.jwt.JwtAuthenticationFilter;
import com.example.backend.auth.jwt.JwtProvider;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.user.repository.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtProvider jwtProvider, UserRepository userRepository) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/v1/auth/**", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new JwtAuthenticationEntryPoint())
                        // 로그인은 했지만 권한이 없음 (일반 회원이 관리자 API 호출) → 403 FORBIDDEN
                        .accessDeniedHandler((request, response, ex) -> {
                            response.setStatus(ErrorCode.FORBIDDEN.getStatus().value());
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"success\":false,\"error\":{\"code\":\"FORBIDDEN\",\"message\":\""
                                    + ErrorCode.FORBIDDEN.getDefaultMessage() + "\",\"details\":[]}}");
                        }))
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new AccountStateFilter(userRepository), JwtAuthenticationFilter.class);
        return http.build();
    }
}
