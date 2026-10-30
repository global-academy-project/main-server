package com.example.backend.auth.internal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InternalTokenFilter: 내부 API 토큰 인증")
class InternalTokenFilterTest {

    private static final String TOKEN = "internal-token-for-test-0123456789abcdef";

    private final InternalTokenFilter filter = new InternalTokenFilter(TOKEN);

    /** 필터가 채운 인증이 다른 테스트로 새지 않게 */
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest request(String uri, String token) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setRequestURI(uri);
        if (token != null) {
            req.addHeader(InternalTokenFilter.HEADER, token);
        }
        return req;
    }

    @Test
    @DisplayName("맞는 토큰이면 다음으로 넘긴다")
    void validToken_passes() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/internal/v1/tools/check_rules", TOKEN), res, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("토큰이 없거나 틀리면 401 UNAUTHORIZED, 다음으로 넘기지 않는다")
    void missingOrWrongToken_401() throws Exception {
        for (String token : new String[]{null, "", TOKEN + "x", "wrong"}) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request("/internal/v1/tools/check_rules", token), res, chain);

            assertThat(chain.getRequest()).isNull();
            assertThat(res.getStatus()).isEqualTo(401);
            assertThat(res.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
        }
    }

    @Test
    @DisplayName("맞는 토큰이면 내부 호출 권한(ROLE_INTERNAL)을 채운다. 경로로 판정하지 않는다 (보안 체인이 경로를 정함)")
    void validToken_setsInternalRole() throws Exception {
        Authentication[] seen = new Authentication[1];
        filter.doFilter(request("/%69nternal/v1/tools/check_rules", TOKEN), new MockHttpServletResponse(),
                (req, res) -> seen[0] = SecurityContextHolder.getContext().getAuthentication());

        assertThat(seen[0]).isNotNull();
        assertThat(seen[0].getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_INTERNAL");
    }

    @Test
    @DisplayName("오류 응답은 공통 봉투 JSON (문자열 조립 아님)")
    void errorBody_isEnvelope() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(request("/internal/v1/tools/check_rules", null), res, new MockFilterChain());

        assertThat(res.getContentType()).startsWith("application/json");
        assertThat(res.getContentAsString()).contains("\"success\":false", "\"details\":[]").doesNotContain("\"data\"");
    }

    @Test
    @DisplayName("토큰 설정이 비었거나 32자 미만이면 서버가 뜨지 않는다")
    void badConfig_failsFast() {
        assertThatThrownBy(() -> new InternalTokenFilter("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new InternalTokenFilter(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new InternalTokenFilter("short-token")).isInstanceOf(IllegalStateException.class);
    }
}
