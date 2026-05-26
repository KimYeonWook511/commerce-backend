package com.commerce.order.infrastructure;

import java.time.Duration;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.commerce.common.log.LogContext;
import com.commerce.order.application.event.OrderIdempotencyCacheEvent;
import com.commerce.order.application.port.OrderIdempotencyStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisOrderIdempotencyStore implements OrderIdempotencyStore {

	private final StringRedisTemplate redisTemplate;

	@Override
	public boolean reserve(Long memberId, String idempotencyKey, Duration ttl) {
		String value = OrderIdempotencyStatus.PROCESSING.value();
		try {
			// NPE 방지
			return Boolean.TRUE.equals(
				redisTemplate.opsForValue().setIfAbsent(buildKey(memberId, idempotencyKey), value, ttl)
			);
		} catch (DataAccessException e) {
			log.warn("Redis reserve 실패, DB fallback으로 전환: {}", e.getMessage());
			return false;
		}
	}

	@Override
	public Optional<Long> getCompletedOrderId(Long memberId, String idempotencyKey) {
		try {
			String value = redisTemplate.opsForValue().get(buildKey(memberId, idempotencyKey));
			return OrderIdempotencyStatus.parseCompletedOrderId(value);
		} catch (DataAccessException e) {
			log.warn("Redis 조회 실패, DB fallback으로 전환: {}", e.getMessage());
			return Optional.empty();
		}
	}

	@Override
	public void complete(Long memberId, String idempotencyKey, Long orderId, Duration ttl) {
		try {
			String value = OrderIdempotencyStatus.completedValue(orderId);
			redisTemplate.opsForValue().set(buildKey(memberId, idempotencyKey), value, ttl);
		} catch (DataAccessException e) {
			log.warn("Redis complete 실패 (무시): {}", e.getMessage());
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

	// RDB 커밋 이후에만 Redis에 캐싱한다. Redis 장애 시 RDB 롤백을 방지하기 위함이다 (ADR-005).
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handle(OrderIdempotencyCacheEvent event) {
		boolean traceIdPushed = pushTraceIdIfValid(event.getTraceId());
		try {
			try {
				complete(event.getMemberId(), event.getIdempotencyKey(), event.getOrderId(), event.getTtl());
			} catch (DataAccessException e) {
				log.warn("Redis AFTER_COMMIT 캐싱 실패 (무시): {}", e.getMessage());
			}
		} finally {
			if (traceIdPushed) {
				LogContext.removeTraceId();
			}
		}
	}

	private boolean pushTraceIdIfValid(String traceId) {
		if (LogContext.isValidTraceId(traceId)) {
			LogContext.putTraceId(traceId);
			return true;
		}
		return false;
	}

	private String buildKey(Long memberId, String idempotencyKey) {
		return "order:idempotency:" + memberId + ":" + idempotencyKey;
	}
}
