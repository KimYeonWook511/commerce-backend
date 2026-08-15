package com.commerce.payment.infrastructure.cache;

import java.time.Duration;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.commerce.payment.application.port.PaymentIdempotencyStore;
import com.commerce.payment.infrastructure.PaymentIdempotencyStoreUnavailableException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisPaymentIdempotencyStore implements PaymentIdempotencyStore {

	private static final String KEY_PREFIX = "payment:idempotency:";
	private static final String PROCESSING_MARKER = "1";

	private final StringRedisTemplate redisTemplate;

	@Override
	public boolean reserve(Long memberId, String idempotencyKey, Duration ttl) {
		try {
			return Boolean.TRUE.equals(
				redisTemplate.opsForValue().setIfAbsent(buildKey(memberId, idempotencyKey), PROCESSING_MARKER, ttl)
			);
		} catch (DataAccessException e) {
			log.error("결제 멱등 선점 실패: memberId={}, key={}", memberId, idempotencyKey, e);
			throw new PaymentIdempotencyStoreUnavailableException(e);
		}
	}

	/** 해제가 실패해도 요청은 이미 끝났다. TTL이 지나면 풀리므로 여기서 더 할 일이 없다 */
	@Override
	public void clear(Long memberId, String idempotencyKey) {
		try {
			redisTemplate.delete(buildKey(memberId, idempotencyKey));
		} catch (DataAccessException e) {
			log.warn("결제 멱등 선점 해제 실패 (무시): {}", e.getMessage());
		}
	}

	private String buildKey(Long memberId, String idempotencyKey) {
		return KEY_PREFIX + memberId + ":" + idempotencyKey;
	}
}
