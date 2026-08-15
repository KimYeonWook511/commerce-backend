package com.commerce.common.exception;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
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

	private HttpStatus statusOf(ErrorCode errorCode) {
		return ErrorCategoryHttpStatus.of(errorCode.getCategory());
	}

	@ExceptionHandler(CustomException.class)
	public ResponseEntity<ApiResponse<Void>> handleCustomException(
		CustomException ex
	) {
		ErrorCode errorCode = ex.getErrorCode();
		if (statusOf(errorCode).is5xxServerError()) {
			log.error("커스텀 예외 발생 code={}", errorCode.getCode(), ex);
		}
		return ResponseEntity.status(statusOf(errorCode))
			.body(ApiResponse.error(errorCode));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleException(
		Exception ex
	) {
		log.error("처리되지 않은 예외 발생", ex);
		return ResponseEntity.status(statusOf(CommonErrorCode.INTERNAL_ERROR))
			.body(ApiResponse.error(CommonErrorCode.INTERNAL_ERROR));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Map<String, String>>> handleMethodArgumentNotValidException(
		MethodArgumentNotValidException ex
	) {
		Map<String, String> errors = new LinkedHashMap<>();
		ex.getBindingResult().getFieldErrors()
			.forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));
		return ResponseEntity.status(statusOf(CommonErrorCode.INVALID_REQUEST))
			.body(ApiResponse.error(CommonErrorCode.INVALID_REQUEST, errors));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadableException(
		HttpMessageNotReadableException ex
	) {
		return ResponseEntity.status(statusOf(CommonErrorCode.INVALID_REQUEST))
			.body(ApiResponse.error(CommonErrorCode.INVALID_REQUEST));
	}

	@ExceptionHandler(OptimisticLockingFailureException.class)
	public ResponseEntity<ApiResponse<Void>> handleOptimisticLockingFailureException(
		OptimisticLockingFailureException ex
	) {
		log.warn("낙관적 락 충돌 발생: {}", ex.getMessage());
		return ResponseEntity.status(statusOf(CommonErrorCode.OPTIMISTIC_LOCK_CONFLICT))
			.body(ApiResponse.error(CommonErrorCode.OPTIMISTIC_LOCK_CONFLICT));
	}

	// 안전망: application 계층에서 catch 누락 시 fallback. 정상 흐름에서 도달하지 않는다.
	// 도달 시 코드 버그를 의미하므로 500으로 응답하고 stack trace를 기록한다.
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolationException(
		DataIntegrityViolationException ex
	) {
		log.error("데이터 무결성 위반 (안전망)", ex);
		return ResponseEntity.status(statusOf(CommonErrorCode.DATA_INTEGRITY_VIOLATION))
			.body(ApiResponse.error(CommonErrorCode.DATA_INTEGRITY_VIOLATION));
	}

	// 락 대기 타임아웃과 데드락을 일반 DB 오류와 갈라 받는다. 재고 복구가 주문 취소와 같은 트랜잭션에
	// 있어 그 경합이 길어지면 취소 전체가 롤백되는데, 섞여 있으면 그 실패가 쌓여도 알 수 없다.
	// 삼키지 않는다 — 구분해 남길 뿐이고 롤백은 그대로 일어난다.
	@ExceptionHandler(PessimisticLockingFailureException.class)
	public ResponseEntity<ApiResponse<Void>> handlePessimisticLockingFailureException(
		PessimisticLockingFailureException ex
	) {
		log.error("락 획득 실패 (안전망)", ex);
		return ResponseEntity.status(statusOf(CommonErrorCode.LOCK_ACQUISITION_FAILED))
			.body(ApiResponse.error(CommonErrorCode.LOCK_ACQUISITION_FAILED));
	}

	// 안전망: 위 구체 DAO 핸들러(DataIntegrityViolationException, OptimisticLockingFailureException,
	// PessimisticLockingFailureException)에 매칭되지 않는 모든 DAO 예외(BadSqlGrammarException 등)를 받는다.
	// stack trace 와 함께 500 으로 응답해 운영 모니터링에서 DAO 카테고리(COMMON-500-2)로 분류된다.
	@ExceptionHandler(DataAccessException.class)
	public ResponseEntity<ApiResponse<Void>> handleDataAccessException(
		DataAccessException ex
	) {
		log.error("DAO 예외 (안전망)", ex);
		return ResponseEntity.status(statusOf(CommonErrorCode.DATA_ACCESS_ERROR))
			.body(ApiResponse.error(CommonErrorCode.DATA_ACCESS_ERROR));
	}
}
