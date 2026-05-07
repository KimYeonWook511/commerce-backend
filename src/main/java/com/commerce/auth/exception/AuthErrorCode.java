package com.commerce.auth.exception;

import org.springframework.http.HttpStatus;

import com.commerce.common.exception.ErrorCode;

public enum AuthErrorCode implements ErrorCode {
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "AUTH-401", "인증이 필요합니다"),
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH-401-1", "이메일 또는 비밀번호가 올바르지 않습니다"),
	TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH-401-2", "토큰이 만료되었습니다"),
	TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH-401-3", "유효하지 않은 토큰입니다"),
	TOKEN_EMPTY(HttpStatus.UNAUTHORIZED, "AUTH-401-4", "토큰이 비어있습니다"),
	REFRESH_TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "AUTH-401-5", "리프레시 토큰이 유효하지 않습니다"),
	FORBIDDEN(HttpStatus.FORBIDDEN, "AUTH-403", "권한이 없습니다");

	private final HttpStatus status;
	private final String code;
	private final String message;

	AuthErrorCode(HttpStatus status, String code, String message) {
		this.status = status;
		this.code = code;
		this.message = message;
	}

	@Override
	public HttpStatus getStatus() {
		return status;
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
