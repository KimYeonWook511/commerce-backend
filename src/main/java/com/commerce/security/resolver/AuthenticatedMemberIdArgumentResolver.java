package com.commerce.security.resolver;

import com.commerce.security.annotation.AuthenticatedMemberId;
import com.commerce.security.context.AuthenticationContext;
import com.commerce.auth.exception.AuthErrorCode;
import com.commerce.auth.exception.AuthException;
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
		Long memberId = AuthenticationContext.getMemberId();
		if (memberId == null) {
			throw new AuthException(AuthErrorCode.UNAUTHORIZED);
		}
		return memberId;
	}
}
