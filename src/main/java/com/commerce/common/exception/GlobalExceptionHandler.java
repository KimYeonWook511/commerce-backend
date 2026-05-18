package com.commerce.common.exception;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;

import com.commerce.common.ApiResponse;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

	@ExceptionHandler(CustomException.class)
	public ResponseEntity<ApiResponse<Void>> handleCustomException(
		CustomException ex
	) {
		log.error("커스텀 예외 발생: {}", ex.getMessage());
		ErrorCode errorCode = ex.getErrorCode();
		return ResponseEntity.status(errorCode.getStatus())
			.body(ApiResponse.error(errorCode));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleException(
		Exception ex
	) {
		log.error("처리되지 않은 예외 발생: {}", ex.getMessage());
		return ResponseEntity.status(CommonErrorCode.INTERNAL_ERROR.getStatus())
			.body(ApiResponse.error(CommonErrorCode.INTERNAL_ERROR));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Map<String, String>>> handleMethodArgumentNotValidException(
		MethodArgumentNotValidException ex
	) {
		log.error("요청 값 검증 실패: {}",  ex.getMessage());
		Map<String, String> errors = new LinkedHashMap<>();
		ex.getBindingResult().getFieldErrors()
			.forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));
		return ResponseEntity.status(CommonErrorCode.INVALID_REQUEST.getStatus())
			.body(ApiResponse.error(CommonErrorCode.INVALID_REQUEST, errors));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadableException(
		HttpMessageNotReadableException ex
	) {
		log.error("요청 본문 파싱 실패: {}", ex.getMessage());
		return ResponseEntity.status(CommonErrorCode.INVALID_REQUEST.getStatus())
			.body(ApiResponse.error(CommonErrorCode.INVALID_REQUEST));
	}

	@ExceptionHandler(OptimisticLockingFailureException.class)
	public ResponseEntity<ApiResponse<Void>> handleOptimisticLockingFailureException(
		OptimisticLockingFailureException ex
	) {
		log.warn("낙관적 락 충돌 발생: {}", ex.getMessage());
		return ResponseEntity.status(CommonErrorCode.OPTIMISTIC_LOCK_CONFLICT.getStatus())
			.body(ApiResponse.error(CommonErrorCode.OPTIMISTIC_LOCK_CONFLICT));
	}

	// 안전망: application 계층에서 catch 누락 시 fallback. 정상 흐름에서 도달하지 않는다.
	// 도달 시 코드 버그를 의미하므로 500으로 응답하고 stack trace를 기록한다.
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolationException(
		DataIntegrityViolationException ex
	) {
		log.error("데이터 무결성 위반 (안전망)", ex);
		return ResponseEntity.status(CommonErrorCode.DATA_INTEGRITY_VIOLATION.getStatus())
			.body(ApiResponse.error(CommonErrorCode.DATA_INTEGRITY_VIOLATION));
	}

	// 안전망: 위 구체 DAO 핸들러(DataIntegrityViolationException, OptimisticLockingFailureException)에
	// 매칭되지 않는 모든 DAO 예외(BadSqlGrammarException, CannotAcquireLockException 등)를 받는다.
	// stack trace 와 함께 500 으로 응답해 운영 모니터링에서 DAO 카테고리(COMMON-500-2)로 분류된다.
	@ExceptionHandler(DataAccessException.class)
	public ResponseEntity<ApiResponse<Void>> handleDataAccessException(
		DataAccessException ex
	) {
		log.error("DAO 예외 (안전망)", ex);
		return ResponseEntity.status(CommonErrorCode.DATA_ACCESS_ERROR.getStatus())
			.body(ApiResponse.error(CommonErrorCode.DATA_ACCESS_ERROR));
	}
}
