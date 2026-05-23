package com.commerce.security.filter;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.commerce.auth.application.TokenAuthenticationService;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class JwtAuthenticationFilterConfig {

	@Bean
	public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilter(
		TokenAuthenticationService tokenAuthenticationService,
		ObjectMapper objectMapper
	) {
		FilterRegistrationBean<JwtAuthenticationFilter> bean =
			new FilterRegistrationBean<>(new JwtAuthenticationFilter(tokenAuthenticationService, objectMapper));
		bean.addUrlPatterns("/*");
		bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
		return bean;
	}
}
