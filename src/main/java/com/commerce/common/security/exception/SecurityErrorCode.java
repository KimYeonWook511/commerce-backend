package com.commerce.common.security.exception;

import com.commerce.common.exception.ErrorCategory;
import com.commerce.common.exception.ErrorCode;

/**
 * security가 스스로 판정하는 인증/인가 실패 코드. 토큰 자체가 없거나(UNAUTHORIZED),
 * 인증은 됐지만 권한이 부족할 때(FORBIDDEN) 쓴다. 토큰 만료·무효 등 "토큰을 검증해야 아는" 코드는
 * auth가 판정해 port 호출로 전파하므로 여기 두지 않는다.
 *
 * code/message는 인증/인가 문제 공간의 계약(api-spec의 AUTH-401/403)을 그대로 따른다.
 * 클래스가 security에 있는 것은 leaf를 지키기 위한 정의 위치일 뿐, 코드는 AUTH 문제 공간에 속한다.
 */
public enum SecurityErrorCode implements ErrorCode {

	UNAUTHORIZED(ErrorCategory.UNAUTHORIZED, "AUTH-401", "인증이 필요합니다"),
	FORBIDDEN(ErrorCategory.FORBIDDEN, "AUTH-403", "권한이 없습니다");

	private final ErrorCategory category;
	private final String code;
	private final String message;

	SecurityErrorCode(ErrorCategory category, String code, String message) {
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
