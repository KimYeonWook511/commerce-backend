package com.commerce.member.domain.exception;

import org.springframework.http.HttpStatus;

import com.commerce.common.exception.ErrorCode;

public enum MemberErrorCode implements ErrorCode {
	MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBER-404", "사용자를 찾을 수 없습니다"),
	DUPLICATE_EMAIL(HttpStatus.CONFLICT, "MEMBER-409", "이미 가입된 이메일입니다");

	private final HttpStatus status;
	private final String code;
	private final String message;

	MemberErrorCode(HttpStatus status, String code, String message) {
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
