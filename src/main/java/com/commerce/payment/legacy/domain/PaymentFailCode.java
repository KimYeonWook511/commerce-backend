package com.commerce.payment.legacy.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentFailCode {
	TIME_EXPIRED("결제 시간 만료"),
	ALREADY_CANCELED("이미 취소 완료됨"),
	AMOUNT_MISMATCH("승인 금액 불일치"),
	MERCHANT_PAY_KEY_MISMATCH("가맹점 결제 키 불일치"),
	INVALID_PG_PAYMENT_ID("유효하지 않은 결제 번호"),
	INVALID_MERCHANT("유효하지 않은 가맹점"),
	OWNER_AUTH_FAILED("본인 인증 실패"),
	NOT_ENOUGH_ACCOUNT_BALANCE("잔액 부족"),
	DUPLICATE_PAYMENT("중복 결제"),
	ORDER_CANCELED("주문 취소로 인한 보상 환불"),
	APPROVE_PROCESS_FAILED("서버 처리 오류로 승인 반영 실패"),
	CANCEL_PROCESS_FAILED("서버 처리 오류로 취소 반영 실패"),
	PG_REQUEST_REJECTED("PG 요청 거절"),
	PG_INVALID_RESPONSE("PG 응답 처리 실패"),
	PG_MAINTENANCE("PG 점검"),
	PG_NETWORK_ERROR("PG 네트워크 오류"),
	PG_SERVER_ERROR("PG 서버 오류");

	private final String description;
}
