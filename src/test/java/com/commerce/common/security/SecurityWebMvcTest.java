package com.commerce.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.commerce.common.log.filter.TraceIdFilterConfig;
import com.commerce.common.log.LogContext;
import com.commerce.common.security.annotation.AuthenticatedMemberId;
import com.commerce.common.security.annotation.RequireRole;
import com.commerce.common.security.config.SecurityWebMvcConfig;
import com.commerce.common.security.context.AuthenticationContext;
import com.commerce.common.security.filter.TokenAuthenticationFilterConfig;
import com.commerce.common.security.interceptor.AuthorizationInterceptor;
import com.commerce.common.security.port.TokenValidator;
import com.commerce.common.security.resolver.AuthenticatedMemberIdArgumentResolver;


@WebMvcTest(controllers = SecurityWebMvcTest.TestController.class)
@AutoConfigureMockMvc(addFilters = true)
@ActiveProfiles("test")
@Import({
	SecurityWebMvcConfig.class,
	AuthenticatedMemberIdArgumentResolver.class,
	AuthorizationInterceptor.class,
	TraceIdFilterConfig.class,
	TokenAuthenticationFilterConfig.class,
	SecurityWebMvcTest.TestController.class
})
class SecurityWebMvcTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private TokenValidator tokenValidator;

	@AfterEach
	void tearDown() {
		MDC.clear();
	}

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
		given(tokenValidator.validate("access-token"))
			.willReturn(new AuthenticationContext(1L, Role.ROLE_USER));

		// when & then
		mockMvc.perform(get("/test/secure")
				.header("Authorization", "Bearer access-token"))
			.andExpect(status().isOk());
	}

	@DisplayName("권한이 없으면 인가 오류를 반환한다")
	@Test
	void adminEndpoint_whenRoleMismatch_returnForbidden() throws Exception {
		// given
		given(tokenValidator.validate("access-token"))
			.willReturn(new AuthenticationContext(1L, Role.ROLE_USER));

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
		given(tokenValidator.validate("access-token"))
			.willReturn(new AuthenticationContext(10L, Role.ROLE_USER));

		// when & then
		mockMvc.perform(get("/test/member-id")
				.header("Authorization", "Bearer access-token"))
			.andExpect(status().isOk())
			.andExpect(content().string("10"));
	}

	@DisplayName("인증 제외 경로는 토큰 인증 서비스를 호출하지 않는다")
	@Test
	void whitelistEndpoint_whenRequested_doNotAuthenticateToken() throws Exception {
		// when & then
		mockMvc.perform(get("/products"))
			.andExpect(status().isOk());

		then(tokenValidator).should(never()).validate(any());
		assertThat(LogContext.getMemberId()).isNull();
	}

	@DisplayName("인증 성공 시 Controller 실행 중 MDC.memberId가 set되고 요청 종료 후 제거된다")
	@Test
	void authenticatedRequest_mdcMemberIdSetInControllerAndRemovedAfter() throws Exception {
		// given
		given(tokenValidator.validate("access-token"))
			.willReturn(new AuthenticationContext(42L, Role.ROLE_USER));

		// when & then
		mockMvc.perform(get("/test/mdc-member-id")
				.header("Authorization", "Bearer access-token"))
			.andExpect(status().isOk())
			.andExpect(content().string("42"));

		assertThat(LogContext.getMemberId()).isNull();
	}

	@RestController
	public static class TestController {

		@GetMapping("/test/secure")
		public ResponseEntity<Void> secure() {
			return ResponseEntity.ok().build();
		}

		@RequireRole(Role.ROLE_ADMIN)
		@GetMapping("/test/admin")
		public ResponseEntity<Void> admin() {
			return ResponseEntity.ok().build();
		}

		@GetMapping("/test/member-id")
		public ResponseEntity<String> memberId(
			@AuthenticatedMemberId Long memberId
		) {
			return ResponseEntity.ok(String.valueOf(memberId));
		}

		@GetMapping("/products")
		public ResponseEntity<Void> products() {
			return ResponseEntity.ok().build();
		}

		@GetMapping("/test/mdc-member-id")
		public ResponseEntity<String> mdcMemberId() {
			return ResponseEntity.ok(LogContext.getMemberId());
		}
	}
}
