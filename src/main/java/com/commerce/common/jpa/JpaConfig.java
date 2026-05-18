package com.commerce.common.jpa;

import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLExceptionTranslator;

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

	// HibernateJpaConfiguration이 SQLExceptionTranslator 빈을 감지해 HibernateJpaDialect에 주입한다.
	// 미설정 시 HibernateJpaDialect가 SQLStateSQLExceptionTranslator를 사용해 unique 위반이
	// DuplicateKeyException 대신 DataIntegrityViolationException으로 올라온다.
	@Bean
	public SQLExceptionTranslator jdbcExceptionTranslator(DataSource dataSource) {
		return new SQLErrorCodeSQLExceptionTranslator(dataSource);
	}

}

