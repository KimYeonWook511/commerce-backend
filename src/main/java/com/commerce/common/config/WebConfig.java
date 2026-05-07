package com.commerce.common.config;

import java.util.List;

import com.commerce.security.interceptor.AuthorizationInterceptor;
import com.commerce.security.resolver.AuthenticatedMemberIdArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.*;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

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
