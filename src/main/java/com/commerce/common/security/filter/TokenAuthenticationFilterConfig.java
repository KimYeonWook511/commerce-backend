package com.commerce.common.security.filter;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.commerce.common.security.port.TokenAuthenticator;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class TokenAuthenticationFilterConfig {

	@Bean
	public FilterRegistrationBean<TokenAuthenticationFilter> tokenAuthenticationFilter(
		TokenAuthenticator tokenAuthenticator,
		ObjectMapper objectMapper
	) {
		FilterRegistrationBean<TokenAuthenticationFilter> bean =
			new FilterRegistrationBean<>(new TokenAuthenticationFilter(tokenAuthenticator, objectMapper));
		bean.addUrlPatterns("/*");
		bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
		return bean;
	}
}
