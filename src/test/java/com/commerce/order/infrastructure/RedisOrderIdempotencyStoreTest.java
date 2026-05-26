package com.commerce.order.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.commerce.common.log.LogContext;
import com.commerce.order.application.event.OrderIdempotencyCacheEvent;

@ExtendWith(MockitoExtension.class)
class RedisOrderIdempotencyStoreTest {

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	private RedisOrderIdempotencyStore store;

	@BeforeEach
	void setUp() {
		store = new RedisOrderIdempotencyStore(redisTemplate);
	}

	@AfterEach
	void cleanUpMdc() {
		LogContext.removeTraceId();
	}

	@DisplayName("유효한 traceId가 동봉된 이벤트는 listener 진입 시 MDC에 복원하고 종료 시 정리한다")
	@Test
	void handle_whenValidTraceId_pushAndRemoveMdc() {
		// given
		String traceId = "trace-abc-111";
		OrderIdempotencyCacheEvent event = new OrderIdempotencyCacheEvent(
			1L, "idem-key", 100L, Duration.ofSeconds(600), traceId);

		AtomicReference<String> capturedTraceId = new AtomicReference<>();
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		doAnswer(invocation -> {
			capturedTraceId.set(LogContext.getTraceId());
			return null;
		}).when(valueOperations).set(anyString(), anyString(), any(Duration.class));

		// when
		store.handle(event);

		// then
		assertThat(capturedTraceId.get()).isEqualTo(traceId);
		assertThat(LogContext.getTraceId()).isNull();
	}

	@DisplayName("traceId가 null인 이벤트는 MDC를 건드리지 않는다")
	@Test
	void handle_whenNullTraceId_doesNotTouchMdc() {
		// given
		LogContext.putTraceId("pre-existing-trace");
		OrderIdempotencyCacheEvent event = new OrderIdempotencyCacheEvent(
			1L, "idem-key", 100L, Duration.ofSeconds(600), null);

		AtomicReference<String> capturedTraceId = new AtomicReference<>();
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		doAnswer(invocation -> {
			capturedTraceId.set(LogContext.getTraceId());
			return null;
		}).when(valueOperations).set(anyString(), anyString(), any(Duration.class));

		// when
		store.handle(event);

		// then
		assertThat(capturedTraceId.get()).isEqualTo("pre-existing-trace");
		assertThat(LogContext.getTraceId()).isEqualTo("pre-existing-trace");
	}

	@DisplayName("traceId 형식이 유효하지 않으면 MDC를 건드리지 않는다")
	@Test
	void handle_whenInvalidTraceId_doesNotTouchMdc() {
		// given
		String invalidTraceId = "invalid trace id with spaces!";
		OrderIdempotencyCacheEvent event = new OrderIdempotencyCacheEvent(
			1L, "idem-key", 100L, Duration.ofSeconds(600), invalidTraceId);

		AtomicReference<String> capturedTraceId = new AtomicReference<>();
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		doAnswer(invocation -> {
			capturedTraceId.set(LogContext.getTraceId());
			return null;
		}).when(valueOperations).set(anyString(), anyString(), any(Duration.class));

		// when
		store.handle(event);

		// then
		assertThat(capturedTraceId.get()).isNull();
		assertThat(LogContext.getTraceId()).isNull();
	}
}
