package com.commerce.order.infrastructure;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.commerce.order.application.port.OrderIdempotencyStore;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RedisOrderIdempotencyStore implements OrderIdempotencyStore {

	private final StringRedisTemplate redisTemplate;

	@Override
	public boolean reserve(Long memberId, String idempotencyKey, Duration ttl) {
		String value = OrderIdempotencyStatus.PROCESSING.value();
		// NPE 방지
		return Boolean.TRUE.equals(
			redisTemplate.opsForValue().setIfAbsent(buildKey(memberId, idempotencyKey), value, ttl)
		);
	}

	@Override
	public Optional<Long> getCompletedOrderId(Long memberId, String idempotencyKey) {
		String value = redisTemplate.opsForValue().get(buildKey(memberId, idempotencyKey));
		return OrderIdempotencyStatus.parseCompletedOrderId(value);
	}

	@Override
	public void complete(Long memberId, String idempotencyKey, Long orderId, Duration ttl) {
		String value = OrderIdempotencyStatus.completedValue(orderId);
		redisTemplate.opsForValue().set(buildKey(memberId, idempotencyKey), value, ttl);
	}

	@Override
	public void clear(Long memberId, String idempotencyKey) {
		redisTemplate.delete(buildKey(memberId, idempotencyKey));
	}

	private String buildKey(Long memberId, String idempotencyKey) {
		return "order:idempotency:" + memberId + ":" + idempotencyKey;
	}
}
