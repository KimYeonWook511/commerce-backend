package com.commerce.support;

public enum CleanupOrder {
	PG_CALL_LOG(1),
	// 환불이 결제를 식별자로 가리킨다. 외래 키는 없지만 순서를 정해 두어야 읽는 사람이 관계를 안다.
	REFUND(2),
	PAYMENT(3),
	LEGACY_PAYMENT_RESERVATION(5),
	LEGACY_PAYMENT(10),
	OUTBOX(20),
	CART(25),
	ORDER(30),
	STOCK(40),
	PRODUCT(50),
	MEMBER(60);

	private final int value;

	CleanupOrder(int value) {
		this.value = value;
	}

	public int value() {
		return this.value;
	}
}
