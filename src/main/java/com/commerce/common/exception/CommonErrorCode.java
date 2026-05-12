package com.commerce.common.exception;

import org.springframework.http.HttpStatus;

public enum CommonErrorCode implements ErrorCode {
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON-500", "예상치 못한 오류가 발생했습니다"),
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "COMMON-400", "요청 값이 올바르지 않습니다"),
	DATA_INTEGRITY_VIOLATION(HttpStatus.CONFLICT, "COMMON-409", "데이터 무결성 위반입니다"),
	OPTIMISTIC_LOCK_CONFLICT(HttpStatus.CONFLICT, "COMMON-409-1", "동시 요청으로 인한 충돌이 발생했습니다");

	private final HttpStatus status;
	private final String code;
	private final String message;

	CommonErrorCode(HttpStatus status, String code, String message) {
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
