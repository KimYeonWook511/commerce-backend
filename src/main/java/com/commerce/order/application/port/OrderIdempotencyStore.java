package com.commerce.order.application.port;

import java.time.Duration;

public interface OrderIdempotencyStore {

	boolean reserve(Long memberId, String idempotencyKey, Duration ttl);

	void clear(Long memberId, String idempotencyKey);
}
