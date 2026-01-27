package com.commerce.payment.exception;

import org.springframework.http.HttpStatus;

import com.commerce.common.exception.ErrorCode;

public enum PaymentErrorCode implements ErrorCode {
	PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAYMENT-404-1", "결제를 찾을 수 없습니다"),
	PAYMENT_PROVIDER_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "PAYMENT-400-1", "지원하지 않는 결제 수단입니다"),
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
