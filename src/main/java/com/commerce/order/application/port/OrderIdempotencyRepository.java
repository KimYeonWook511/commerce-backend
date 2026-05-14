package com.commerce.order.application.port;

import java.time.Duration;
import java.util.Optional;

public interface OrderIdempotencyRepository {

	boolean reserve(Long memberId, String idempotencyKey, Duration ttl);

	Optional<Long> getCompletedOrderId(Long memberId, String idempotencyKey);

	void complete(Long memberId, String idempotencyKey, Long orderId, Duration ttl);

	void clear(Long memberId, String idempotencyKey);
}
