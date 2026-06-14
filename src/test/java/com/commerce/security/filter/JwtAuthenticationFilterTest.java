package com.commerce.security.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.commerce.auth.application.TokenAuthenticationService;
import com.commerce.auth.application.result.TokenAuthenticationResult;
import com.commerce.auth.domain.exception.AuthErrorCode;
import com.commerce.auth.domain.exception.AuthException;
import com.commerce.common.log.LogContext;
import com.commerce.common.log.filter.AccessLogFilter;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

class JwtAuthenticationFilterTest {

	private final TokenAuthenticationService tokenAuthenticationService = mock(TokenAuthenticationService.class);
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenAuthenticationService, objectMapper);

	@BeforeEach
	void setUp() {
		MDC.clear();
	}

	@AfterEach
	void tearDown() {
		MDC.clear();
	}

	@DisplayName("인증 성공 시 chain.doFilter 실행 중 MDC.memberId가 set된다")
	@Test
	void authSuccess_mdcMemberIdSetDuringChain() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
		request.addHeader("Authorization", "Bearer valid-token");
		MockHttpServletResponse response = new MockHttpServletResponse();

		when(tokenAuthenticationService.authenticateAccessToken("valid-token"))
			.thenReturn(TokenAuthenticationResult.of(42L, "ROLE_USER"));

		AtomicReference<String> mdcDuringChain = new AtomicReference<>();
		FilterChain chain = mock(FilterChain.class);
		doAnswer(invocation -> {
			mdcDuringChain.set(LogContext.getMemberId());
			return null;
		}).when(chain).doFilter(any(), any());

		filter.doFilter(request, response, chain);

		assertThat(mdcDuringChain.get()).isEqualTo("42");
	}

	@DisplayName("인증 성공 시 chain.doFilter 실행 중 request attribute에 memberId가 set된다")
	@Test
	void authSuccess_requestAttributeSetDuringChain() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
		request.addHeader("Authorization", "Bearer valid-token");
		MockHttpServletResponse response = new MockHttpServletResponse();

		when(tokenAuthenticationService.authenticateAccessToken("valid-token"))
			.thenReturn(TokenAuthenticationResult.of(42L, "ROLE_USER"));

		AtomicReference<Object> attributeDuringChain = new AtomicReference<>();
		FilterChain chain = mock(FilterChain.class);
		doAnswer(invocation -> {
			attributeDuringChain.set(request.getAttribute(AccessLogFilter.MEMBER_ID_ATTRIBUTE));
			return null;
		}).when(chain).doFilter(any(), any());

		filter.doFilter(request, response, chain);

		assertThat(attributeDuringChain.get()).isEqualTo(42L);
	}

	@DisplayName("doFilter 완료 후 MDC에서 memberId가 제거된다")
	@Test
	void afterDoFilter_mdcMemberIdIsNull() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
		request.addHeader("Authorization", "Bearer valid-token");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		when(tokenAuthenticationService.authenticateAccessToken("valid-token"))
			.thenReturn(TokenAuthenticationResult.of(42L, "ROLE_USER"));

		filter.doFilter(request, response, chain);

		assertThat(LogContext.getMemberId()).isNull();
	}

	@DisplayName("토큰 누락 시 401을 반환하고 MDC와 attribute에 memberId가 없다")
	@Test
	void tokenMissing_unauthorizedAndNoMdcOrAttribute() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(LogContext.getMemberId()).isNull();
		assertThat(request.getAttribute(AccessLogFilter.MEMBER_ID_ATTRIBUTE)).isNull();
	}

	@DisplayName("인증 실패(CustomException) 시 401을 반환하고 MDC 잔류가 없다")
	@Test
	void authFailure_customException_unauthorizedAndNoMdcResidue() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
		request.addHeader("Authorization", "Bearer invalid-token");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		when(tokenAuthenticationService.authenticateAccessToken("invalid-token"))
			.thenThrow(new AuthException(AuthErrorCode.TOKEN_INVALID));

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(LogContext.getMemberId()).isNull();
	}

	@DisplayName("WHITELIST 경로(/products)는 MDC.put을 호출하지 않고 attribute를 set하지 않는다")
	@Test
	void whitelistPath_noMdcPutAndNoAttribute() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/products");
		MockHttpServletResponse response = new MockHttpServletResponse();

		AtomicReference<String> mdcDuringChain = new AtomicReference<>();
		AtomicReference<Object> attributeDuringChain = new AtomicReference<>();
		FilterChain chain = mock(FilterChain.class);
		doAnswer(invocation -> {
			mdcDuringChain.set(LogContext.getMemberId());
			attributeDuringChain.set(request.getAttribute(AccessLogFilter.MEMBER_ID_ATTRIBUTE));
			return null;
		}).when(chain).doFilter(any(), any());

		filter.doFilter(request, response, chain);

		assertThat(mdcDuringChain.get()).isNull();
		assertThat(attributeDuringChain.get()).isNull();
		assertThat(LogContext.getMemberId()).isNull();
	}

	@DisplayName("chain.doFilter에서 예외가 발생해도 finally에서 MDC.remove가 보장된다")
	@Test
	void chainException_mdcRemovedInFinally() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
		request.addHeader("Authorization", "Bearer valid-token");
		MockHttpServletResponse response = new MockHttpServletResponse();

		when(tokenAuthenticationService.authenticateAccessToken("valid-token"))
			.thenReturn(TokenAuthenticationResult.of(42L, "ROLE_USER"));

		FilterChain chain = mock(FilterChain.class);
		doThrow(new RuntimeException("체인 내부 예외")).when(chain).doFilter(any(), any());

		filter.doFilter(request, response, chain);

		assertThat(LogContext.getMemberId()).isNull();
	}

	@DisplayName("스레드 풀 재사용 시나리오: 이전 요청의 MDC 값이 잔류해도 새 값으로 덮어쓰고 완료 후 제거된다")
	@Test
	void threadPoolReuse_prevMdcValueIsOverwrittenAndRemovedAfterRequest() throws Exception {
		LogContext.putMemberId(999L);

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
		request.addHeader("Authorization", "Bearer valid-token");
		MockHttpServletResponse response = new MockHttpServletResponse();

		when(tokenAuthenticationService.authenticateAccessToken("valid-token"))
			.thenReturn(TokenAuthenticationResult.of(99L, "ROLE_USER"));

		AtomicReference<String> mdcDuringChain = new AtomicReference<>();
		FilterChain chain = mock(FilterChain.class);
		doAnswer(invocation -> {
			mdcDuringChain.set(LogContext.getMemberId());
			return null;
		}).when(chain).doFilter(any(), any());

		filter.doFilter(request, response, chain);

		assertThat(mdcDuringChain.get()).isEqualTo("99");
		assertThat(LogContext.getMemberId()).isNull();
	}
}
