package com.commerce.payment.domain.exception;

import com.commerce.common.exception.ErrorCategory;
import com.commerce.common.exception.ErrorCode;

public enum PaymentErrorCode implements ErrorCode {

	PAYMENT_CONCURRENTLY_MODIFIED(ErrorCategory.CONFLICT, "PAYMENT-409-10",
		"다른 처리가 먼저 결제 상태를 변경했습니다"),
	REFUND_CONCURRENTLY_MODIFIED(ErrorCategory.CONFLICT, "PAYMENT-409-11",
		"다른 처리가 먼저 환불 상태를 변경했습니다"),

	REFUND_AMOUNT_INVALID(ErrorCategory.INVALID, "PAYMENT-400-20", "환불 금액은 0보다 커야 합니다"),
	REFUND_IDEMPOTENCY_KEY_REQUIRED(ErrorCategory.INVALID, "PAYMENT-400-21", "환불 요청 멱등키가 필요합니다"),

	PAYMENT_STATUS_TRANSITION_NOT_ALLOWED(ErrorCategory.INTERNAL, "PAYMENT-500-10",
		"결제 상태 전이가 허용되지 않습니다"),
	PAYMENT_CLOSE_CODE_NOT_ALLOWED(ErrorCategory.INTERNAL, "PAYMENT-500-11",
		"그 종결 상태의 종결 코드가 아닙니다"),
	// 승인 금액이 0이면 한도가 처음부터 0이라 되돌릴 환불을 만들 수 없다. 정상 경로에서 나올 수 없는 값이므로
	// 확정을 막고, 부르는 쪽이 그 결제를 결과 불명으로 남긴다.
	PAYMENT_APPROVED_AMOUNT_INVALID(ErrorCategory.INTERNAL, "PAYMENT-500-12",
		"승인 금액이 0보다 크지 않습니다"),
	REFUND_STATUS_TRANSITION_NOT_ALLOWED(ErrorCategory.INTERNAL, "PAYMENT-500-13",
		"환불 상태 전이가 허용되지 않습니다");

	private final ErrorCategory category;
	private final String code;
	private final String message;

	PaymentErrorCode(ErrorCategory category, String code, String message) {
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
