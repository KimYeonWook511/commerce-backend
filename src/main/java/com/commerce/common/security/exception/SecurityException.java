package com.commerce.common.security.exception;

import com.commerce.common.exception.CustomException;
import com.commerce.common.exception.ErrorCode;

/**
 * security가 던지는 인증/인가 예외. 공통 {@code CustomException}을 상속해 GlobalExceptionHandler가
 * DispatcherServlet 안에서(예: resolver) 매핑할 수 있다. 필터는 예외를 던지지 않고 응답을 직접 쓴다.
 */
public class SecurityException extends CustomException {

	public SecurityException(ErrorCode errorCode) {
		super(errorCode);
	}
}
