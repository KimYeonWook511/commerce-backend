package com.commerce.auth.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.commerce.auth.filter.JwtAuthenticationFilter;
import com.commerce.auth.interceptor.AuthorizationInterceptor;
import com.commerce.auth.jwt.JwtTokenValidator;
import com.commerce.auth.resolver.AuthenticatedMemberIdArgumentResolver;
import com.commerce.common.config.WebConfig;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

@WebMvcTest(controllers = AuthTestController.class)
@AutoConfigureMockMvc(addFilters = true)
@ActiveProfiles("test")
@Import({
	WebConfig.class,
	AuthenticatedMemberIdArgumentResolver.class,
	AuthorizationInterceptor.class,
	JwtAuthenticationFilter.class
})
class AuthWebSecurityTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private JwtTokenValidator jwtTokenValidator;

	@DisplayName("토큰이 없으면 인증 오류를 반환한다")
	@Test
	void secureEndpoint_whenTokenMissing_returnUnauthorized() throws Exception {
		// when & then
		mockMvc.perform(get("/test/secure"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH-401"))
			.andExpect(jsonPath("$.message").value("인증이 필요합니다"))
			.andExpect(jsonPath("$.data").value(Matchers.nullValue()));
	}

	@DisplayName("유효한 토큰이면 보호된 엔드포인트를 통과한다")
	@Test
	void secureEndpoint_whenTokenValid_returnOk() throws Exception {
		// given
		Claims claims = Jwts.claims().setSubject("1");
		claims.put("role", "ROLE_USER");
		given(jwtTokenValidator.validateAccessToken("access-token")).willReturn(claims);

		// when & then
		mockMvc.perform(get("/test/secure")
				.header("Authorization", "Bearer access-token"))
			.andExpect(status().isOk());
	}

	@DisplayName("권한이 없으면 인가 오류를 반환한다")
	@Test
	void adminEndpoint_whenRoleMismatch_returnForbidden() throws Exception {
		// given
		Claims claims = Jwts.claims().setSubject("1");
		claims.put("role", "ROLE_USER");
		given(jwtTokenValidator.validateAccessToken("access-token")).willReturn(claims);

		// when & then
		mockMvc.perform(get("/test/admin")
				.header("Authorization", "Bearer access-token"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH-403"))
			.andExpect(jsonPath("$.message").value("권한이 없습니다"))
			.andExpect(jsonPath("$.data").value(Matchers.nullValue()));
	}

	@DisplayName("인증된 사용자면 인증 식별자를 주입한다")
	@Test
	void memberIdEndpoint_whenAuthenticated_returnMemberId() throws Exception {
		// given
		Claims claims = Jwts.claims().setSubject("10");
		claims.put("role", "ROLE_USER");
		given(jwtTokenValidator.validateAccessToken("access-token")).willReturn(claims);

		// when & then
		mockMvc.perform(get("/test/member-id")
				.header("Authorization", "Bearer access-token"))
			.andExpect(status().isOk())
			.andExpect(content().string("10"));
	}
}
