package com.commerce.payment.exception;

import org.springframework.http.HttpStatus;

import com.commerce.common.exception.ErrorCode;

public enum PaymentErrorCode implements ErrorCode {
	PAYMENT_STATUS_NOT_ALLOWED(HttpStatus.CONFLICT, "PAYMENT-409-1", "결제 상태 변경이 허용되지 않습니다");

	private final HttpStatus status;
	private final String code;
	private final String message;

	PaymentErrorCode(HttpStatus status, String code, String message) {
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
