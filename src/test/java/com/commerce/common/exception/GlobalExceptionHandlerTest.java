package com.commerce.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

import com.commerce.common.ApiResponse;
import com.commerce.member.domain.exception.MemberErrorCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

class GlobalExceptionHandlerTest {

	private GlobalExceptionHandler handler;
	private ListAppender<ILoggingEvent> listAppender;
	private Logger logger;

	@BeforeEach
	void setUp() {
		handler = new GlobalExceptionHandler();
		logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
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
	@DisplayName("4xx CustomException은 로그를 남기지 않는다")
	void handleCustomException_4xx_noLog() {
		// Given
		CustomException ex = new CustomException(MemberErrorCode.MEMBER_NOT_FOUND);

		// When
		ResponseEntity<?> response = handler.handleCustomException(ex);

		// Then
		assertThat(listAppender.list).isEmpty();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("5xx CustomException은 ERROR 로그와 throwable을 남긴다")
	void handleCustomException_5xx_errorWithThrowable() {
		// Given
		CustomException ex = new CustomException(CommonErrorCode.INTERNAL_ERROR);

		// When
		ResponseEntity<?> response = handler.handleCustomException(ex);

		// Then
		assertThat(listAppender.list).hasSize(1);
		ILoggingEvent event = listAppender.list.get(0);
		assertThat(event.getLevel()).isEqualTo(Level.ERROR);
		assertThat(event.getThrowableProxy()).isNotNull();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}

	@Test
	@DisplayName("처리되지 않은 예외는 ERROR 로그와 throwable을 남긴다 (stack trace 보존)")
	void handleException_errorWithThrowable() {
		// Given
		RuntimeException ex = new RuntimeException("테스트 예외");

		// When
		ResponseEntity<?> response = handler.handleException(ex);

		// Then
		assertThat(listAppender.list).hasSize(1);
		ILoggingEvent event = listAppender.list.get(0);
		assertThat(event.getLevel()).isEqualTo(Level.ERROR);
		assertThat(event.getThrowableProxy()).isNotNull();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}

	@Test
	@DisplayName("MethodArgumentNotValidException은 로그를 남기지 않는다")
	void handleMethodArgumentNotValidException_noLog() {
		// Given
		MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
		BindingResult bindingResult = mock(BindingResult.class);
		when(ex.getBindingResult()).thenReturn(bindingResult);
		when(bindingResult.getFieldErrors()).thenReturn(Collections.emptyList());

		// When
		ResponseEntity<?> response = handler.handleMethodArgumentNotValidException(ex);

		// Then
		assertThat(listAppender.list).isEmpty();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("HttpMessageNotReadableException은 로그를 남기지 않는다")
	void handleHttpMessageNotReadableException_noLog() {
		// Given
		HttpMessageNotReadableException ex = new HttpMessageNotReadableException("파싱 실패");

		// When
		ResponseEntity<?> response = handler.handleHttpMessageNotReadableException(ex);

		// Then
		assertThat(listAppender.list).isEmpty();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("OptimisticLockingFailureException은 WARN 로그를 남기고 throwable을 포함하지 않는다")
	void handleOptimisticLockingFailureException_warnNoThrowable() {
		// Given
		OptimisticLockingFailureException ex = new OptimisticLockingFailureException("낙관적 락 충돌");

		// When
		ResponseEntity<?> response = handler.handleOptimisticLockingFailureException(ex);

		// Then
		assertThat(listAppender.list).hasSize(1);
		ILoggingEvent event = listAppender.list.get(0);
		assertThat(event.getLevel()).isEqualTo(Level.WARN);
		assertThat(event.getThrowableProxy()).isNull();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	@DisplayName("DataIntegrityViolationException은 ERROR 로그와 throwable을 남긴다")
	void handleDataIntegrityViolationException_errorWithThrowable() {
		// Given
		DataIntegrityViolationException ex = new DataIntegrityViolationException("무결성 위반");

		// When
		ResponseEntity<?> response = handler.handleDataIntegrityViolationException(ex);

		// Then
		assertThat(listAppender.list).hasSize(1);
		ILoggingEvent event = listAppender.list.get(0);
		assertThat(event.getLevel()).isEqualTo(Level.ERROR);
		assertThat(event.getThrowableProxy()).isNotNull();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}

	@Test
	@DisplayName("DataAccessException은 ERROR 로그와 throwable을 남긴다")
	void handleDataAccessException_errorWithThrowable() {
		// Given
		DataAccessException ex = new DataAccessException("DAO 오류") {};

		// When
		ResponseEntity<?> response = handler.handleDataAccessException(ex);

		// Then
		assertThat(listAppender.list).hasSize(1);
		ILoggingEvent event = listAppender.list.get(0);
		assertThat(event.getLevel()).isEqualTo(Level.ERROR);
		assertThat(event.getThrowableProxy()).isNotNull();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}

	@Test
	@DisplayName("락 대기 타임아웃과 데드락은 일반 DAO 오류와 구분된 코드로 남는다")
	void handlePessimisticLockingFailureException_separatesLockContention() {
		// Given: 락 대기 타임아웃과 데드락이 그 아래에 함께 걸린다
		CannotAcquireLockException waitTimeout = new CannotAcquireLockException("락 대기 시간 초과");
		DeadlockLoserDataAccessException deadlock =
			new DeadlockLoserDataAccessException("데드락", new RuntimeException("cause"));

		// When
		ResponseEntity<ApiResponse<Void>> timeoutResponse =
			handler.handlePessimisticLockingFailureException(waitTimeout);
		ResponseEntity<ApiResponse<Void>> deadlockResponse =
			handler.handlePessimisticLockingFailureException(deadlock);

		// Then: 회원 응답 상태는 기존 실패 그대로이고 코드만 갈린다
		assertThat(timeoutResponse.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(deadlockResponse.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(timeoutResponse.getBody().getCode())
			.isEqualTo(CommonErrorCode.LOCK_ACQUISITION_FAILED.getCode())
			.isNotEqualTo(CommonErrorCode.DATA_ACCESS_ERROR.getCode());
		assertThat(listAppender.list).hasSize(2);
		assertThat(listAppender.list.get(0).getLevel()).isEqualTo(Level.ERROR);
		assertThat(listAppender.list.get(0).getThrowableProxy()).isNotNull();
	}

	@Test
	@DisplayName("각 핸들러의 응답 status 코드가 변경되지 않는다")
	void allHandlers_responseStatusUnchanged() {
		// Given
		MethodArgumentNotValidException methodArgEx = mock(MethodArgumentNotValidException.class);
		BindingResult bindingResult = mock(BindingResult.class);
		when(methodArgEx.getBindingResult()).thenReturn(bindingResult);
		when(bindingResult.getFieldErrors()).thenReturn(Collections.emptyList());

		// When & Then
		assertThat(handler.handleCustomException(new CustomException(MemberErrorCode.MEMBER_NOT_FOUND))
			.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(handler.handleCustomException(new CustomException(CommonErrorCode.INTERNAL_ERROR))
			.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(handler.handleException(new RuntimeException("런타임 예외"))
			.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(handler.handleMethodArgumentNotValidException(methodArgEx)
			.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(handler.handleHttpMessageNotReadableException(new HttpMessageNotReadableException("파싱 실패"))
			.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(handler.handleOptimisticLockingFailureException(new OptimisticLockingFailureException("낙관적 락"))
			.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(handler.handleDataIntegrityViolationException(new DataIntegrityViolationException("무결성 위반"))
			.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(handler.handleDataAccessException(new DataAccessException("DAO 오류") {})
			.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(handler.handlePessimisticLockingFailureException(new CannotAcquireLockException("락 대기"))
			.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}
}
