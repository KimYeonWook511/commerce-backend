package com.commerce.order.domain.exception;

import org.springframework.http.HttpStatus;

import com.commerce.common.exception.ErrorCode;

public enum OrderErrorCode implements ErrorCode {
	ORDER_ITEMS_EMPTY(HttpStatus.BAD_REQUEST, "ORDER-400-1", "주문 상품이 없습니다"),
	ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER-404", "주문을 찾을 수 없습니다"),
	ORDER_IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT, "ORDER-409-1", "주문 생성이 이미 처리 중입니다. 잠시 후 다시 시도해주세요."),
	ORDER_CANCEL_NOT_ALLOWED(HttpStatus.CONFLICT, "ORDER-409-2", "주문을 취소할 수 없습니다"),
	ORDER_ALREADY_PAID(HttpStatus.CONFLICT, "ORDER-409-6", "주문이 이미 결제 완료 상태입니다"),
	ORDER_CANCELED_FOR_PAYMENT(HttpStatus.CONFLICT, "ORDER-409-7", "취소된 주문은 결제를 완료할 수 없습니다"),
	ORDER_INVALID_STATE_FOR_PAYMENT(HttpStatus.CONFLICT, "ORDER-409-8", "현재 주문 상태에서는 결제를 완료할 수 없습니다"),
	ORDER_PAYMENT_NOT_ALLOWED(HttpStatus.CONFLICT, "ORDER-409-4", "주문 결제를 진행할 수 없습니다"),
	ORDER_REFUND_NOT_AVAILABLE(HttpStatus.CONFLICT, "ORDER-409-5", "결제 확인 중인 주문은 지금 취소할 수 없습니다. 잠시 후 다시 시도해 주세요."),
	ORDER_REFUND_TARGET_NOT_FOUND(HttpStatus.INTERNAL_SERVER_ERROR, "ORDER-500-1", "환불 대상 결제를 찾을 수 없습니다");

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
