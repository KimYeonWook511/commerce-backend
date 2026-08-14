package com.commerce.payment.application.dto;

import com.commerce.payment.domain.PaymentPg;

/**
 * 결제 시작 요청. {@code idempotencyKey}는 클라이언트가 요청마다 만들어 헤더로 싣는 값이고,
 * 재시도할 때는 같은 값이 다시 온다.
 */
public record StartPaymentCommand(
	Long memberId,
	Long orderId,
	PaymentPg pg,
	String idempotencyKey
) {
}
