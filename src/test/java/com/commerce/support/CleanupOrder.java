package com.commerce.support;

public enum CleanupOrder {
	PAYMENT(10),
	OUTBOX(20),
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
