package com.commerce.payment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentReasonCode {
	USER_CANCELED("사용자 결제 취소"),
	TIME_EXPIRED("결제 시간 만료"),
	UNDER_AGE_LIMIT("미성년자 결제 한도 초과"),
	AMOUNT_MISMATCH("승인 금액 불일치"),
	MERCHANT_PAY_KEY_MISMATCH("가맹점 결제 키 불일치"),
	INVALID_PAYMENT_ID("유효하지 않은 결제 번호"),
	PG_MAINTENANCE("PG 점검"),
	PG_NETWORK_ERROR("PG 네트워크 오류"),
	PG_SERVER_ERROR("PG 서버 오류"),
	APPROVAL_FAILED("결제 승인 실패"),
	PRE_APPROVE_FAILED("승인 전 결제 실패"),
	CANCEL_REQUESTED_BY_SYSTEM("시스템 취소 요청"),
	CANCEL_REQUESTED_BY_ADMIN("관리자 취소 요청"),
	CANCEL_FAILED("결제 취소 실패"),
	UNKNOWN("알 수 없는 사유");

	private final String description;
}
