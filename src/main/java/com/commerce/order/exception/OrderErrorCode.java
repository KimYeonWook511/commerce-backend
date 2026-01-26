package com.commerce.order.exception;

import org.springframework.http.HttpStatus;

import com.commerce.common.exception.ErrorCode;

public enum OrderErrorCode implements ErrorCode {
	ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER-404", "주문을 찾을 수 없습니다"),
	ORDER_IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT, "ORDER-409-1", "주문 생성이 처리 중입니다"),
	ORDER_CANCEL_NOT_ALLOWED(HttpStatus.CONFLICT, "ORDER-409-2", "주문을 취소할 수 없습니다");

	private final HttpStatus status;
	private final String code;
	private final String message;

	OrderErrorCode(HttpStatus status, String code, String message) {
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
