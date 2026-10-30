package com.example.backend.config;

import com.example.backend.auth.internal.InternalTokenFilter;
import com.example.backend.auth.jwt.JwtProvider;
import com.example.backend.site.entity.Site;
import com.example.backend.site.entity.SiteMode;
import com.example.backend.site.repository.SiteRepository;
import com.example.backend.user.entity.User;
import com.example.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 보안 체인 2개를 실제 필터 체인으로 확인 (끝나면 롤백).
 *  - 내부 API: 토큰 없음·틀림 401, 맞으면 통과. 인코딩된 경로(/%69nternal)로 토큰 검사를 건너뛸 수 없다
 *  - 웹 JWT 로는 내부 API 를 부를 수 없다
 *  - 만료된 access token 은 401 TOKEN_EXPIRED (웹이 refresh 를 시도하는 기준)
 */
@SpringBootTest
@Transactional
@DisplayName("보안 체인: 내부 API 토큰, 인코딩 경로 우회, 만료 토큰")
class SecurityChainTest {

    private static final String CHECK_RULES = "/internal/v1/tools/check_rules";

    @Autowired WebApplicationContext context;
    @Autowired JwtProvider jwtProvider;
    @Autowired UserRepository userRepository;
    @Autowired SiteRepository siteRepository;
    @Value("${app.internal.token}") String internalToken;
    @Value("${app.jwt.secret}") String jwtSecret;

    private MockMvc mvc;
    private Long userId;
    /** 실제 판정까지 가면 200 + BLOCK 이 나오는 본문 (밀폐 산소 17.5%) */
    private String blockBody;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String tag = UUID.randomUUID().toString().substring(0, 8);
        userId = userRepository.save(User.bootstrapAdmin("sec-" + tag + "@example.com", "HASH", LocalDateTime.now())).getId();
        Long siteId = siteRepository.save(new Site("보안테스트-" + tag, SiteMode.VERIFY)).getId();
        blockBody = """
                {"siteId": %d, "workType": "CONFINED", "input": {"measurement": {"O2": 17.5}}}
                """.formatted(siteId);
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private MockHttpServletRequestBuilder checkRules(String token) {
        MockHttpServletRequestBuilder b = post(CHECK_RULES).contentType(MediaType.APPLICATION_JSON).content(blockBody);
        return token == null ? b : b.header(InternalTokenFilter.HEADER, token);
    }

    @Test
    @DisplayName("맞는 토큰이면 내부 API 가 실제로 판정한다 (200, BLOCK)")
    void internal_validToken_reachesController() throws Exception {
        MvcResult r = perform(checkRules(internalToken));

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).contains("\"result\":\"BLOCK\"");
    }

    @Test
    @DisplayName("토큰이 없거나 틀리면 401 UNAUTHORIZED (공통 봉투)")
    void internal_missingOrWrongToken_401() throws Exception {
        for (String token : new String[]{null, "wrong-token", internalToken + "x"}) {
            MvcResult r = perform(checkRules(token));

            assertThat(r.getResponse().getStatus()).as("token=%s", token).isEqualTo(401);
            assertThat(body(r)).contains("\"success\":false", "\"code\":\"UNAUTHORIZED\"");
        }
    }

    @Test
    @DisplayName("인코딩된 경로(/%69nternal = /internal)로 보내도 토큰 없이 판정까지 가지 않는다")
    void internal_encodedPath_cannotBypass() throws Exception {
        MvcResult r = perform(post(URI.create("/%69nternal/v1/tools/check_rules"))
                .contentType(MediaType.APPLICATION_JSON).content(blockBody));

        // 보안 체인이 막으면 401, 방화벽이 이상한 경로로 거절하면 400. 어느 쪽이든 컨트롤러(200)까지 가면 안 된다
        assertThat(r.getResponse().getStatus()).isIn(400, 401);
        assertThat(body(r)).doesNotContain("\"result\"");
    }

    @Test
    @DisplayName("웹 로그인 토큰(JWT)으로는 내부 API 를 부를 수 없다")
    void internal_rejectsWebJwt() throws Exception {
        String jwt = jwtProvider.createToken(userId);
        MvcResult r = perform(checkRules(null).header("Authorization", "Bearer " + jwt));

        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(r)).doesNotContain("\"result\"");
    }

    @Test
    @DisplayName("내부 토큰으로는 웹 API 를 부를 수 없다 (401)")
    void web_rejectsInternalToken() throws Exception {
        MvcResult r = perform(get("/api/v1/users/me").header(InternalTokenFilter.HEADER, internalToken));

        assertThat(r.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("만료된 access token 이면 401 TOKEN_EXPIRED, 위조 토큰이면 401 UNAUTHORIZED")
    void web_expiredToken_tokenExpired() throws Exception {
        String expired = new JwtProvider(jwtSecret, -60).createToken(userId);
        MvcResult r = perform(get("/api/v1/users/me").header("Authorization", "Bearer " + expired));

        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(r)).contains("\"code\":\"TOKEN_EXPIRED\"");

        MvcResult forged = perform(get("/api/v1/users/me").header("Authorization", "Bearer not-a-jwt"));
        assertThat(forged.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(forged)).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    @DisplayName("유효한 토큰이면 웹 API 가 응답한다 (기준 확인)")
    void web_validToken_ok() throws Exception {
        MvcResult r = perform(get("/api/v1/users/me").header("Authorization", "Bearer " + jwtProvider.createToken(userId)));

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
    }
}
