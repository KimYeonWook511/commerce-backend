package com.commerce.auth.presentation;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.commerce.auth.domain.exception.AuthErrorCode;
import com.commerce.auth.infrastructure.RefreshTokenStoreUnavailableException;
import com.commerce.common.ApiResponse;
import com.commerce.common.exception.ErrorCategoryHttpStatus;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class AuthExceptionHandler {

	@ExceptionHandler(RefreshTokenStoreUnavailableException.class)
	public ResponseEntity<ApiResponse<Void>> handleRefreshTokenStoreUnavailable(
		RefreshTokenStoreUnavailableException ex
	) {
		return ResponseEntity.status(ErrorCategoryHttpStatus.of(AuthErrorCode.INTERNAL_ERROR.getCategory()))
			.body(ApiResponse.error(AuthErrorCode.INTERNAL_ERROR));
	}
}
