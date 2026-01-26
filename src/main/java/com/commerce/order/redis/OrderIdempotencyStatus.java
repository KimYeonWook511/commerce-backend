package com.commerce.order.redis;

import java.util.Optional;

public enum OrderIdempotencyStatus {
	PROCESSING("PROCESSING"),
	COMPLETED("COMPLETED"),
	FAILED("FAILED");

	private static final String COMPLETED_DELIMITER = ":";

	private final String value;

	OrderIdempotencyStatus(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}

	public static String completedValue(Long orderId) {
		return COMPLETED.value + COMPLETED_DELIMITER + orderId;
	}

	public static Optional<Long> parseCompletedOrderId(String storedValue) {
		String prefix = COMPLETED.value + COMPLETED_DELIMITER;
		if (storedValue == null || !storedValue.startsWith(prefix)) {
			return Optional.empty();
		}
		String orderIdValue = storedValue.substring(prefix.length());
		return Optional.of(Long.valueOf(orderIdValue));
	}
}
