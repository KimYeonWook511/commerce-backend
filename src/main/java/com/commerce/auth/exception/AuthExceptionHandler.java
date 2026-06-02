package com.commerce.auth.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.commerce.common.ApiResponse;

@RestControllerAdvice
public class AuthExceptionHandler {

	@ExceptionHandler(RefreshTokenStoreUnavailableException.class)
	public ResponseEntity<ApiResponse<Void>> handleRefreshTokenStoreUnavailable(
		RefreshTokenStoreUnavailableException ex
	) {
		return ResponseEntity.status(AuthErrorCode.INTERNAL_ERROR.getStatus())
			.body(ApiResponse.error(AuthErrorCode.INTERNAL_ERROR));
	}
}
