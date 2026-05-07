package com.commerce.security.interceptor;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.commerce.security.annotation.RequireRole;
import com.commerce.security.context.AuthenticationContext;
import com.commerce.auth.exception.AuthErrorCode;
import com.commerce.common.ApiResponse;
import com.commerce.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class AuthorizationInterceptor implements HandlerInterceptor {

	private final ObjectMapper objectMapper;

	public AuthorizationInterceptor(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public boolean preHandle(
		HttpServletRequest request,
		HttpServletResponse response,
		Object handler
	) throws Exception {

		if (!(handler instanceof HandlerMethod hm)) {
			return true;
		}

		RequireRole requireRole = hm.getMethodAnnotation(RequireRole.class);
		if (requireRole == null) {
			return true;
		}

		String role = AuthenticationContext.getRole();
		if (role == null || !role.equals(requireRole.value().name())) {
			ErrorCode errorCode = AuthErrorCode.FORBIDDEN;
			response.setStatus(errorCode.getStatus().value());
			response.setCharacterEncoding("UTF-8");
			response.setContentType("application/json");
			ApiResponse<Void> body = ApiResponse.error(errorCode);
			response.getWriter().write(objectMapper.writeValueAsString(body));
			return false;
		}

		return true;
	}
}
