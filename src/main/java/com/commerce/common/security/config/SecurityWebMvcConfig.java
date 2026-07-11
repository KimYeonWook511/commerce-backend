package com.commerce.common.security.config;

import java.util.List;

import com.commerce.common.security.interceptor.AuthorizationInterceptor;
import com.commerce.common.security.resolver.AuthenticatedMemberIdArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.*;

import lombok.RequiredArgsConstructor;

/**
 * authz 진입점(interceptor·argument resolver)을 스스로 등록하는 self-registration.
 * security가 자기 웹 구성을 소유해 common/config에 의존하지 않는다(leaf).
 */
@Configuration
@RequiredArgsConstructor
public class SecurityWebMvcConfig implements WebMvcConfigurer {

	private final AuthenticatedMemberIdArgumentResolver authenticatedMemberIdArgumentResolver;
	private final AuthorizationInterceptor authorizationInterceptor;

	@Override
	public void addArgumentResolvers(
		List<HandlerMethodArgumentResolver> argumentResolvers
	) {
		argumentResolvers.add(authenticatedMemberIdArgumentResolver);
	}

	@Override
	public void addInterceptors(
		InterceptorRegistry registry
	) {
		registry.addInterceptor(authorizationInterceptor);
	}
}
