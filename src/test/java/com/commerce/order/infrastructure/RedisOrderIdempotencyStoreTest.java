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

	@DisplayName("MDC가 비어있고 이벤트에 valid traceId가 있으면 listener 진입 시 MDC에 복원하고 종료 시 정리한다 (비동기 fallback)")
	@Test
	void handle_whenMdcEmptyAndValidTraceId_pushAndRemoveMdc() {
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

	@DisplayName("MDC에 이미 traceId가 있으면 이벤트 traceId를 무시하고 기존 값을 보존한다 (동기 실행 경로)")
	@Test
	void handle_whenMdcAlreadyHasTraceId_preservesExistingMdc() {
		// given
		String existingTraceId = "pre-existing-trace";
		String eventTraceId = "event-trace-different";
		LogContext.putTraceId(existingTraceId);
		OrderIdempotencyCacheEvent event = new OrderIdempotencyCacheEvent(
			1L, "idem-key", 100L, Duration.ofSeconds(600), eventTraceId);

		AtomicReference<String> capturedTraceId = new AtomicReference<>();
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		doAnswer(invocation -> {
			capturedTraceId.set(LogContext.getTraceId());
			return null;
		}).when(valueOperations).set(anyString(), anyString(), any(Duration.class));

		// when
		store.handle(event);

		// then
		assertThat(capturedTraceId.get()).isEqualTo(existingTraceId);
		assertThat(LogContext.getTraceId()).isEqualTo(existingTraceId);
	}

	@DisplayName("MDC에 이미 traceId가 있으면 이벤트 traceId가 null이어도 기존 값을 보존한다")
	@Test
	void handle_whenMdcAlreadyHasTraceIdAndEventTraceIdNull_preservesExistingMdc() {
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

	@DisplayName("MDC가 비어있고 이벤트 traceId도 null이면 MDC를 건드리지 않는다")
	@Test
	void handle_whenMdcEmptyAndEventTraceIdNull_doesNotTouchMdc() {
		// given
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
		assertThat(capturedTraceId.get()).isNull();
		assertThat(LogContext.getTraceId()).isNull();
	}

	@DisplayName("MDC가 비어있고 이벤트 traceId 형식이 유효하지 않으면 MDC를 건드리지 않는다")
	@Test
	void handle_whenMdcEmptyAndInvalidTraceId_doesNotTouchMdc() {
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
