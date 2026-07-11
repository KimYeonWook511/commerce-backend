package com.commerce.common.security.interceptor;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.commerce.common.ApiResponse;
import com.commerce.common.exception.ErrorCategoryHttpStatus;
import com.commerce.common.exception.ErrorCode;
import com.commerce.common.security.Role;
import com.commerce.common.security.annotation.RequireRole;
import com.commerce.common.security.context.AuthenticationContext;
import com.commerce.common.security.context.AuthenticationContextHolder;
import com.commerce.common.security.exception.SecurityErrorCode;
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

		AuthenticationContext context = AuthenticationContextHolder.get();
		Role role = context == null ? null : context.role();
		if (role == null || role != requireRole.value()) {
			ErrorCode errorCode = SecurityErrorCode.FORBIDDEN;
			response.setStatus(ErrorCategoryHttpStatus.of(errorCode.getCategory()).value());
			response.setCharacterEncoding("UTF-8");
			response.setContentType("application/json");
			ApiResponse<Void> body = ApiResponse.error(errorCode);
			response.getWriter().write(objectMapper.writeValueAsString(body));
			return false;
		}

		return true;
	}
}
