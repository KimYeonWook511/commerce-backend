package com.commerce.common.security.resolver;

import com.commerce.common.security.annotation.AuthenticatedMemberId;
import com.commerce.common.security.context.AuthenticationContext;
import com.commerce.common.security.context.AuthenticationContextHolder;
import com.commerce.common.security.exception.SecurityErrorCode;
import com.commerce.common.security.exception.SecurityException;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;

@Component
public class AuthenticatedMemberIdArgumentResolver
	implements HandlerMethodArgumentResolver {

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(AuthenticatedMemberId.class)
			&& parameter.getParameterType().equals(Long.class);
	}

	@Override
	public Object resolveArgument(
		MethodParameter parameter,
		ModelAndViewContainer mavContainer,
		NativeWebRequest webRequest,
		WebDataBinderFactory binderFactory
	) {
		AuthenticationContext context = AuthenticationContextHolder.get();
		Long memberId = context == null ? null : context.memberId();
		if (memberId == null) {
			throw new SecurityException(SecurityErrorCode.UNAUTHORIZED);
		}
		return memberId;
	}
}
