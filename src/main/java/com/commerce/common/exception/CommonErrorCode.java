package com.commerce.common.exception;

public enum CommonErrorCode implements ErrorCode {
	INTERNAL_ERROR(ErrorCategory.INTERNAL, "COMMON-500", "예상치 못한 오류가 발생했습니다"),
	INVALID_REQUEST(ErrorCategory.INVALID, "COMMON-400", "요청 값이 올바르지 않습니다"),
	DATA_INTEGRITY_VIOLATION(ErrorCategory.INTERNAL, "COMMON-500-1", "데이터 무결성 위반이 발생했습니다"),
	DATA_ACCESS_ERROR(ErrorCategory.INTERNAL, "COMMON-500-2", "데이터 접근 중 오류가 발생했습니다"),
	OPTIMISTIC_LOCK_CONFLICT(ErrorCategory.CONFLICT, "COMMON-409-1", "동시 요청으로 인한 충돌이 발생했습니다");

	private final ErrorCategory category;
	private final String code;
	private final String message;

	CommonErrorCode(ErrorCategory category, String code, String message) {
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
