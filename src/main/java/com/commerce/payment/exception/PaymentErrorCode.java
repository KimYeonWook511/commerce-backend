package com.commerce.payment.exception;

import org.springframework.http.HttpStatus;

import com.commerce.common.exception.ErrorCode;

public enum PaymentErrorCode implements ErrorCode {
	PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAYMENT-404-1", "결제를 찾을 수 없습니다"),
	PAYMENT_ATTEMPT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAYMENT-404-2", "결제 시도 이력을 찾을 수 없습니다"),
	PAYMENT_PROVIDER_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "PAYMENT-400-1", "지원하지 않는 결제 수단입니다"),
	PAYMENT_APPROVE_FAILED(HttpStatus.BAD_REQUEST, "PAYMENT-400-2", "결제 승인 처리에 실패했습니다"),
	PAYMENT_MERCHANT_KEY_MISMATCH(HttpStatus.BAD_REQUEST, "PAYMENT-400-3", "merchantPayKey가 일치하지 않습니다"),
	PAYMENT_INVALID_MERCHANT(HttpStatus.BAD_REQUEST, "PAYMENT-400-4", "유효하지 않은 가맹점입니다"),
	PAYMENT_PG_NETWORK_ERROR(HttpStatus.BAD_GATEWAY, "PAYMENT-502-1", "PG 네트워크 오류가 발생했습니다"),
	PAYMENT_ALREADY_CANCELED(HttpStatus.BAD_REQUEST, "PAYMENT-400-5", "이미 취소된 결제입니다"),
	PAYMENT_TIME_EXPIRED(HttpStatus.BAD_REQUEST, "PAYMENT-400-6", "결제 시간이 초과되었습니다"),
	PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "PAYMENT-400-8", "결제 금액이 일치하지 않습니다"),
	PAYMENT_OWNER_AUTH_FAILED(HttpStatus.BAD_REQUEST, "PAYMENT-400-9", "본인 인증에 실패했습니다"),
	PAYMENT_NOT_ENOUGH_ACCOUNT_BALANCE(HttpStatus.BAD_REQUEST, "PAYMENT-400-10", "잔액이 부족합니다"),
	PAYMENT_PG_REQUEST_REJECTED(HttpStatus.BAD_REQUEST, "PAYMENT-400-11", "PG 요청이 거절되었습니다"),
	PAYMENT_APPROVE_STATUS_CHECK_FAILED(HttpStatus.BAD_REQUEST, "PAYMENT-400-12", "결제 승인 상태 확인에 실패했습니다"),
	PAYMENT_CANCEL_FAILED(HttpStatus.BAD_REQUEST, "PAYMENT-400-13", "결제 취소 처리에 실패했습니다"),
	PAYMENT_PG_MAINTENANCE(HttpStatus.BAD_GATEWAY, "PAYMENT-502-4", "PG 점검 중입니다"),
	PAYMENT_PG_SERVER_ERROR(HttpStatus.BAD_GATEWAY, "PAYMENT-502-2", "PG 서버 오류가 발생했습니다"),
	PAYMENT_PG_INVALID_RESPONSE(HttpStatus.BAD_GATEWAY, "PAYMENT-502-3", "PG 응답이 올바르지 않습니다"),
	PAYMENT_DUPLICATE(HttpStatus.CONFLICT, "PAYMENT-409-2", "이미 다른 결제가 완료된 주문입니다"),
	PAYMENT_STATUS_NOT_ALLOWED(HttpStatus.CONFLICT, "PAYMENT-409-1", "결제 상태 변경이 허용되지 않습니다"),
	PAYMENT_ATTEMPT_AMOUNT_MISMATCH(HttpStatus.CONFLICT, "PAYMENT-409-3",
		"결제 시도 이력의 금액과 요청 금액이 일치하지 않습니다"),
	PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED(HttpStatus.INTERNAL_SERVER_ERROR, "PAYMENT-500-1",
		"결제 시도 상태 전이가 허용되지 않습니다"),
	PAYMENT_ATTEMPT_TYPE_MISMATCH(HttpStatus.INTERNAL_SERVER_ERROR, "PAYMENT-500-2",
		"결제 시도 타입과 mark 요청이 일치하지 않습니다"),
	PAYMENT_RESERVATION_STATUS_TRANSITION_NOT_ALLOWED(HttpStatus.INTERNAL_SERVER_ERROR, "PAYMENT-500-3",
		"결제 예약 상태 전이가 허용되지 않습니다"),
	PAYMENT_MEMBER_MISMATCH(HttpStatus.FORBIDDEN, "PAYMENT-403-1", "결제 예약의 회원 정보가 일치하지 않습니다"),
	PAYMENT_RESERVATION_NOT_REUSABLE(HttpStatus.CONFLICT, "PAYMENT-409-4", "재사용할 수 없는 결제 예약입니다"),
	PAYMENT_RESULT_PENDING(HttpStatus.ACCEPTED, "PAYMENT-202-1", "결제 결과를 아직 확인할 수 없습니다");

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
