package com.commerce.common.jpa;

import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing
public class JpaConfig {

	// @Bean
	// public AuditorAware<String> auditorProvider() {
	// 	return () -> {
	// 		String user = UUID.randomUUID().toString(); // 추후 session이나 토큰에서 값을 가져오기
	// 		return Optional.of(user);
	// 	};
	// }

}

