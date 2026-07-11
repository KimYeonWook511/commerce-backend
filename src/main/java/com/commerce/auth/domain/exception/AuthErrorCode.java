package com.commerce.auth.domain.exception;

import com.commerce.common.exception.ErrorCategory;
import com.commerce.common.exception.ErrorCode;

public enum AuthErrorCode implements ErrorCode {
	UNAUTHORIZED(ErrorCategory.UNAUTHORIZED, "AUTH-401", "인증이 필요합니다"),
	INVALID_CREDENTIALS(ErrorCategory.UNAUTHORIZED, "AUTH-401-1", "이메일 또는 비밀번호가 올바르지 않습니다"),
	TOKEN_EXPIRED(ErrorCategory.UNAUTHORIZED, "AUTH-401-2", "토큰이 만료되었습니다"),
	TOKEN_INVALID(ErrorCategory.UNAUTHORIZED, "AUTH-401-3", "유효하지 않은 토큰입니다"),
	TOKEN_EMPTY(ErrorCategory.UNAUTHORIZED, "AUTH-401-4", "토큰이 비어있습니다"),
	REFRESH_TOKEN_NOT_FOUND(ErrorCategory.UNAUTHORIZED, "AUTH-401-5", "리프레시 토큰이 유효하지 않습니다"),
	FORBIDDEN(ErrorCategory.FORBIDDEN, "AUTH-403", "권한이 없습니다"),
	INTERNAL_ERROR(ErrorCategory.INTERNAL, "AUTH-500-1", "인증 처리 중 오류가 발생했습니다");

	private final ErrorCategory category;
	private final String code;
	private final String message;

	AuthErrorCode(ErrorCategory category, String code, String message) {
		this.category = category;
		this.code = code;
		this.message = message;
	}

	@Override
	public ErrorCategory getCategory() {
		return category;
	}

	@Override
	public String getCode() {
		return code;
	}

	@Override
	public String getMessage() {
		return message;
	}
}
