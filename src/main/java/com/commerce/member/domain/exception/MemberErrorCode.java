package com.commerce.member.domain.exception;

import com.commerce.common.exception.ErrorCategory;
import com.commerce.common.exception.ErrorCode;

public enum MemberErrorCode implements ErrorCode {
	MEMBER_NOT_FOUND(ErrorCategory.NOT_FOUND, "MEMBER-404", "사용자를 찾을 수 없습니다"),
	DUPLICATE_EMAIL(ErrorCategory.CONFLICT, "MEMBER-409", "이미 가입된 이메일입니다");

	private final ErrorCategory category;
	private final String code;
	private final String message;

	MemberErrorCode(ErrorCategory category, String code, String message) {
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
