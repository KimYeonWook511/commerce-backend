package com.commerce.payment.naverpay.exception;

public class NaverPayException extends RuntimeException {

	private final NaverPayErrorCode errorCode;

	public NaverPayException(NaverPayErrorCode errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public NaverPayException(NaverPayErrorCode errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
	}

	public NaverPayErrorCode getErrorCode() {
		return errorCode;
	}
}
