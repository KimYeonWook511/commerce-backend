package com.commerce.common.log.filter;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class AccessLogFilterConfig {

	@Bean
	public FilterRegistrationBean<AccessLogFilter> accessLogFilter() {
		FilterRegistrationBean<AccessLogFilter> bean = new FilterRegistrationBean<>(new AccessLogFilter());
		bean.addUrlPatterns("/*");
		bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
		return bean;
	}
}
