package com.commerce.payment.infrastructure;

/**
 * 결제 멱등 선점 저장소를 지금 쓸 수 없다는 것.
 * 응용 계층이 잡아 DB 유일 제약 안전망 경로로 물러난다 — 선점 층이 죽었다고 결제 시작이 막히면 안 된다.
 */
public class PaymentIdempotencyStoreUnavailableException extends RuntimeException {

	public PaymentIdempotencyStoreUnavailableException(Throwable cause) {
		super(cause);
	}
}
