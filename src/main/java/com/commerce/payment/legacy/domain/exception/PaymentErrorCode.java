package com.commerce.payment.legacy.domain.exception;

import com.commerce.common.exception.ErrorCategory;
import com.commerce.common.exception.ErrorCode;

public enum PaymentErrorCode implements ErrorCode {
	PAYMENT_NOT_FOUND(ErrorCategory.NOT_FOUND, "PAYMENT-404-1", "결제를 찾을 수 없습니다"),
	PAYMENT_RECORD_NOT_FOUND(ErrorCategory.NOT_FOUND, "PAYMENT-404-2", "결제 시도 이력을 찾을 수 없습니다"),
	PAYMENT_RESERVATION_NOT_FOUND(ErrorCategory.NOT_FOUND, "PAYMENT-404-3", "결제 예약을 찾을 수 없습니다"),
	PAYMENT_PROVIDER_NOT_SUPPORTED(ErrorCategory.INVALID, "PAYMENT-400-1", "지원하지 않는 결제 수단입니다"),
	PAYMENT_APPROVE_FAILED(ErrorCategory.INVALID, "PAYMENT-400-2", "결제 승인 처리에 실패했습니다"),
	PAYMENT_MERCHANT_KEY_MISMATCH(ErrorCategory.INVALID, "PAYMENT-400-3", "merchantPayKey가 일치하지 않습니다"),
	PAYMENT_INVALID_MERCHANT(ErrorCategory.INVALID, "PAYMENT-400-4", "유효하지 않은 가맹점입니다"),
	PAYMENT_PG_NETWORK_ERROR(ErrorCategory.UPSTREAM_ERROR, "PAYMENT-502-1", "PG 네트워크 오류가 발생했습니다"),
	PAYMENT_ALREADY_CANCELED(ErrorCategory.INVALID, "PAYMENT-400-5", "이미 취소된 결제입니다"),
	PAYMENT_TIME_EXPIRED(ErrorCategory.INVALID, "PAYMENT-400-6", "결제 시간이 초과되었습니다"),
	PAYMENT_AMOUNT_MISMATCH(ErrorCategory.INVALID, "PAYMENT-400-8", "결제 금액이 일치하지 않습니다"),
	PAYMENT_OWNER_AUTH_FAILED(ErrorCategory.INVALID, "PAYMENT-400-9", "본인 인증에 실패했습니다"),
	PAYMENT_NOT_ENOUGH_ACCOUNT_BALANCE(ErrorCategory.INVALID, "PAYMENT-400-10", "잔액이 부족합니다"),
	PAYMENT_PG_REQUEST_REJECTED(ErrorCategory.INVALID, "PAYMENT-400-11", "PG 요청이 거절되었습니다"),
	PAYMENT_APPROVE_STATUS_CHECK_FAILED(ErrorCategory.INVALID, "PAYMENT-400-12", "결제 승인 상태 확인에 실패했습니다"),
	PAYMENT_CANCEL_FAILED(ErrorCategory.INVALID, "PAYMENT-400-13", "결제 취소 처리에 실패했습니다"),
	PAYMENT_PG_MAINTENANCE(ErrorCategory.UPSTREAM_ERROR, "PAYMENT-502-4", "PG 점검 중입니다"),
	PAYMENT_PG_SERVER_ERROR(ErrorCategory.UPSTREAM_ERROR, "PAYMENT-502-2", "PG 서버 오류가 발생했습니다"),
	PAYMENT_PG_INVALID_RESPONSE(ErrorCategory.UPSTREAM_ERROR, "PAYMENT-502-3", "PG 응답이 올바르지 않습니다"),
	PAYMENT_DUPLICATE(ErrorCategory.CONFLICT, "PAYMENT-409-2", "이미 다른 결제가 완료된 주문입니다"),
	PAYMENT_RECORD_AMOUNT_MISMATCH(ErrorCategory.CONFLICT, "PAYMENT-409-3",
		"결제 시도 이력의 금액과 요청 금액이 일치하지 않습니다"),
	PAYMENT_STATUS_TRANSITION_NOT_ALLOWED(ErrorCategory.INTERNAL, "PAYMENT-500-1",
		"결제 시도 상태 전이가 허용되지 않습니다"),
	PAYMENT_RESERVATION_STATUS_TRANSITION_NOT_ALLOWED(ErrorCategory.INTERNAL, "PAYMENT-500-3",
		"결제 예약 상태 전이가 허용되지 않습니다"),
	PAYMENT_RESULT_PENDING(ErrorCategory.CONFLICT, "PAYMENT-409-5", "결제 결과 확인 중입니다. 잠시 후 주문 내역에서 확인해 주세요."),
	PAYMENT_RESERVATION_ALREADY_USED(ErrorCategory.CONFLICT, "PAYMENT-409-4", "이미 다른 승인이 예약을 소비했습니다"),
	PAYMENT_CONCURRENTLY_MODIFIED(ErrorCategory.CONFLICT, "PAYMENT-409-6", "다른 처리가 먼저 결제 상태를 변경했습니다");

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
