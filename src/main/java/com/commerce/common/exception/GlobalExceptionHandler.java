package com.commerce.common.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
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

	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolationException(
		DataIntegrityViolationException ex
	) {
		log.error("데이터 무결성 위반: {}", ex.getMessage());
		return ResponseEntity.status(CommonErrorCode.DATA_INTEGRITY_VIOLATION.getStatus())
			.body(ApiResponse.error(CommonErrorCode.DATA_INTEGRITY_VIOLATION));
	}
}
