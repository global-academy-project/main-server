package com.example.backend.auth.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InternalTokenFilter: /internal/** 토큰 인증")
class InternalTokenFilterTest {

    private static final String TOKEN = "internal-token-for-test-0123456789abcdef";

    private final InternalTokenFilter filter = new InternalTokenFilter(TOKEN);

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
    @DisplayName("/internal/ 밖의 경로는 건드리지 않는다 (JWT 쪽이 처리)")
    void otherPaths_notFiltered() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/v1/rules/check", null), res, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("토큰 설정이 비었거나 32자 미만이면 서버가 뜨지 않는다")
    void badConfig_failsFast() {
        assertThatThrownBy(() -> new InternalTokenFilter("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new InternalTokenFilter(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new InternalTokenFilter("short-token")).isInstanceOf(IllegalStateException.class);
    }
}
