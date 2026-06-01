package com.commerce.order.infrastructure;

import java.time.Duration;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.commerce.order.application.port.OrderIdempotencyStore;
import com.commerce.order.exception.OrderIdempotencyStoreUnavailableException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisOrderIdempotencyStore implements OrderIdempotencyStore {

	private static final String PROCESSING_MARKER = "1";

	private final StringRedisTemplate redisTemplate;

	@Override
	public boolean reserve(Long memberId, String idempotencyKey, Duration ttl) {
		try {
			// NPE 방지
			return Boolean.TRUE.equals(
				redisTemplate.opsForValue().setIfAbsent(buildKey(memberId, idempotencyKey), PROCESSING_MARKER, ttl)
			);
		} catch (DataAccessException e) {
			log.error("Redis reserve 실패: memberId={}, key={}", memberId, idempotencyKey, e);
			throw new OrderIdempotencyStoreUnavailableException(e);
		}
	}

	@Override
	public void clear(Long memberId, String idempotencyKey) {
		try {
			redisTemplate.delete(buildKey(memberId, idempotencyKey));
		} catch (DataAccessException e) {
			log.warn("Redis clear 실패 (무시): {}", e.getMessage());
		}
	}

	private String buildKey(Long memberId, String idempotencyKey) {
		return "order:idempotency:" + memberId + ":" + idempotencyKey;
	}
}
