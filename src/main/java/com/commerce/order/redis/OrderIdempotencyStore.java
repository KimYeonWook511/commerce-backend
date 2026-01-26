package com.commerce.order.redis;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class OrderIdempotencyStore {

	private final StringRedisTemplate redisTemplate;

	public boolean reserve(Long memberId, String idempotencyKey, Duration ttl) {
		String value = OrderIdempotencyStatus.PROCESSING.value();
		// NPE 방지
		return Boolean.TRUE.equals(
			redisTemplate.opsForValue().setIfAbsent(buildKey(memberId, idempotencyKey), value, ttl)
		);
	}

	public Optional<Long> getCompletedOrderId(Long memberId, String idempotencyKey) {
		String value = redisTemplate.opsForValue().get(buildKey(memberId, idempotencyKey));
		return OrderIdempotencyStatus.parseCompletedOrderId(value);
	}

	public void complete(Long memberId, String idempotencyKey, Long orderId, Duration ttl) {
		String value = OrderIdempotencyStatus.completedValue(orderId);
		redisTemplate.opsForValue().set(buildKey(memberId, idempotencyKey), value, ttl);
	}

	public void clear(Long memberId, String idempotencyKey) {
		redisTemplate.delete(buildKey(memberId, idempotencyKey));
	}

	private String buildKey(Long memberId, String idempotencyKey) {
		return "order:idempotency:" + memberId + ":" + idempotencyKey;
	}
}
