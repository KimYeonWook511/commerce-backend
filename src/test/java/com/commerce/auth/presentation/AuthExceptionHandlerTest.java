package com.commerce.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.commerce.auth.domain.exception.AuthErrorCode;
import com.commerce.auth.infrastructure.RefreshTokenStoreUnavailableException;
import com.commerce.common.ApiResponse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class AuthExceptionHandlerTest {

	private AuthExceptionHandler handler;
	private ListAppender<ILoggingEvent> listAppender;
	private Logger logger;

	@BeforeEach
	void setUp() {
		handler = new AuthExceptionHandler();
		logger = (Logger) LoggerFactory.getLogger(AuthExceptionHandler.class);
		logger.setLevel(Level.TRACE);
		listAppender = new ListAppender<>();
		listAppender.start();
		logger.addAppender(listAppender);
	}

	@AfterEach
	void tearDown() {
		logger.detachAppender(listAppender);
		logger.setLevel(null);
	}

	@Test
	@DisplayName("RefreshTokenStoreUnavailableException 처리 시 HTTP 500 + AUTH-500-1 응답을 반환한다")
	void handleRefreshTokenStoreUnavailable_returns500WithAuthCode() {
		// given
		RefreshTokenStoreUnavailableException ex =
			new RefreshTokenStoreUnavailableException(new RuntimeException("Redis down"));

		// when
		ResponseEntity<ApiResponse<Void>> response = handler.handleRefreshTokenStoreUnavailable(ex);

		// then
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getCode()).isEqualTo(AuthErrorCode.INTERNAL_ERROR.getCode());
	}

	@Test
	@DisplayName("RefreshTokenStoreUnavailableException 처리 시 로그를 남기지 않는다")
	void handleRefreshTokenStoreUnavailable_noLog() {
		// given
		RefreshTokenStoreUnavailableException ex =
			new RefreshTokenStoreUnavailableException(new RuntimeException("Redis down"));

		// when
		handler.handleRefreshTokenStoreUnavailable(ex);

		// then
		assertThat(listAppender.list).isEmpty();
	}
}
