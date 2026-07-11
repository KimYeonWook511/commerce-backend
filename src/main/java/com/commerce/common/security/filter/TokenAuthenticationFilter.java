package com.commerce.common.security.filter;

import java.io.IOException;
import java.util.Set;

import org.springframework.web.filter.OncePerRequestFilter;

import com.commerce.common.ApiResponse;
import com.commerce.common.exception.CustomException;
import com.commerce.common.exception.ErrorCategoryHttpStatus;
import com.commerce.common.exception.ErrorCode;
import com.commerce.common.log.LogContext;
import com.commerce.common.security.context.AuthenticationContext;
import com.commerce.common.security.context.AuthenticationContextHolder;
import com.commerce.common.security.exception.SecurityErrorCode;
import com.commerce.common.security.port.TokenAuthenticator;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class TokenAuthenticationFilter extends OncePerRequestFilter {

	// 나중에 설정 파일로 분리하기
	private static final Set<String> WHITELIST = Set.of(
		"/auth/login",
		"/auth/signup",
		"/auth/reissue",
		"/products",
		"/payments/naverpay/return"
	);

	private final TokenAuthenticator tokenAuthenticator;
	private final ObjectMapper objectMapper;

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {

		// 인증이 필요 없는 경로 요청
		String requestUri = request.getRequestURI();
		if (WHITELIST.contains(requestUri) || requestUri.startsWith("/products/")) {
			filterChain.doFilter(request, response);
			return;
		}

		// Request Header에서 토큰 가져오기
		String token = resolveToken(request);
		if (token == null) {
			unauthorized(response, SecurityErrorCode.UNAUTHORIZED);
			return;
		}

		// 토큰 인증 — 인증 실패만 401로 응답한다. doFilter(후속 체인·컨트롤러)는 이 catch 밖에 둬,
		// 후속 비즈니스 로직 예외가 401로 오처리되지 않고 정상 전파(GlobalExceptionHandler 등)되게 한다.
		AuthenticationContext context;
		try {
			context = tokenAuthenticator.authenticate(token);
		} catch (CustomException e) {
			// 토큰 만료·무효 등 auth가 판정한 코드를 공통 베이스로 받아 그대로 전파한다.
			unauthorized(response, e.getErrorCode());
			return;
		} catch (Exception e) {
			unauthorized(response, SecurityErrorCode.UNAUTHORIZED);
			return;
		}

		try {
			// memberId, role Context에 저장
			AuthenticationContextHolder.set(context);
			LogContext.putMemberId(context.memberId());

			filterChain.doFilter(request, response);
		} finally {
			// 반드시 정리
			AuthenticationContextHolder.clear();
		}
	}

	private String resolveToken(HttpServletRequest request) {
		String header = request.getHeader("Authorization");
		if (header != null && header.startsWith("Bearer ")) {
			return header.substring(7);
		}
		return null;
	}

	private void unauthorized(HttpServletResponse response, ErrorCode errorCode) throws IOException {
		response.setStatus(ErrorCategoryHttpStatus.of(errorCode.getCategory()).value());
		response.setCharacterEncoding("UTF-8");
		response.setContentType("application/json");
		ApiResponse<Void> body = ApiResponse.error(errorCode);
		response.getWriter().write(objectMapper.writeValueAsString(body));
	}

}
