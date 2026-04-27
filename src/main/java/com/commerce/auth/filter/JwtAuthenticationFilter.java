package com.commerce.auth.filter;

import java.io.IOException;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.commerce.auth.context.AuthenticationContext;
import com.commerce.auth.exception.AuthErrorCode;
import com.commerce.auth.exception.AuthException;
import com.commerce.auth.jwt.JwtTokenValidator;
import com.commerce.common.ApiResponse;
import com.commerce.common.exception.CustomException;
import com.commerce.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	// 나중에 설정 파일로 분리하기
	private static final Set<String> WHITELIST = Set.of(
		"/auth/login",
		"/auth/signup",
		"/auth/reissue",
		"/products",
		"/payments/naverpay/return"
	);

	private final JwtTokenValidator jwtTokenValidator;
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
			unauthorized(response, new AuthException(AuthErrorCode.UNAUTHORIZED));
			return;
		}

		// 토큰 처리
		try {
			Claims claims = jwtTokenValidator.validateAccessToken(token);
			Long memberId = Long.parseLong(
				claims.getSubject()); // claims.get("sub", Long.class); 안 됨 | JWT 표준 스펙(RFC 7519)
			String role = claims.get("role", String.class);

			// memberId, role Context에 저장
			AuthenticationContext.set(memberId, role);

			filterChain.doFilter(request, response);

		} catch (CustomException e) {
			unauthorized(response, e);
		} catch (Exception e) {
			unauthorized(response);
		} finally {
			// 반드시 정리
			AuthenticationContext.clear();
		}
	}

	private String resolveToken(HttpServletRequest request) {
		String header = request.getHeader("Authorization");
		if (header != null && header.startsWith("Bearer ")) {
			return header.substring(7);
		}
		return null;
	}

	private void unauthorized(HttpServletResponse response, CustomException ex) throws IOException {
		ErrorCode errorCode = ex.getErrorCode();
		response.setStatus(errorCode.getStatus().value());
		response.setCharacterEncoding("UTF-8");
		response.setContentType("application/json");
		ApiResponse<Void> body = ApiResponse.error(errorCode);
		response.getWriter().write(objectMapper.writeValueAsString(body));
	}

	private void unauthorized(HttpServletResponse response) throws IOException {
		ErrorCode errorCode = AuthErrorCode.UNAUTHORIZED; // 필요시 커스텀 ErrorCode 생성하기
		response.setStatus(errorCode.getStatus().value());
		response.setCharacterEncoding("UTF-8");
		response.setContentType("application/json");
		ApiResponse<Void> body = ApiResponse.error(errorCode);
		response.getWriter().write(objectMapper.writeValueAsString(body));
	}

}
