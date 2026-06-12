package com.commerce.order.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.commerce.order.exception.OrderIdempotencyStoreUnavailableException;

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

	@DisplayName("키가 없으면 reserve는 true를 반환한다")
	@Test
	void reserve_whenKeyAbsent_returnTrue() {
		// given
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).willReturn(true);

		// when
		boolean result = store.reserve(1L, "idem-key", Duration.ofSeconds(60));

		// then
		assertThat(result).isTrue();
	}

	@DisplayName("키가 이미 존재하면 reserve는 false를 반환한다")
	@Test
	void reserve_whenKeyExists_returnFalse() {
		// given
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).willReturn(false);

		// when
		boolean result = store.reserve(1L, "idem-key", Duration.ofSeconds(60));

		// then
		assertThat(result).isFalse();
	}

	@DisplayName("reserve 시 DataAccessException이 발생하면 OrderIdempotencyStoreUnavailableException을 던진다")
	@Test
	void reserve_whenDataAccessException_throwsStoreUnavailable() {
		// given
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
			.willThrow(new QueryTimeoutException("timeout"));

		// when & then
		assertThatThrownBy(() -> store.reserve(1L, "idem-key", Duration.ofSeconds(60)))
			.isInstanceOf(OrderIdempotencyStoreUnavailableException.class)
			.hasCauseInstanceOf(QueryTimeoutException.class);
	}

	@DisplayName("clear 호출 시 Redis key를 삭제한다")
	@Test
	void clear_whenCalled_deleteKey() {
		// when
		store.clear(1L, "idem-key");

		// then
		then(redisTemplate).should().delete("order:idempotency:1:idem-key");
	}

	@DisplayName("clear 시 DataAccessException이 발생해도 예외를 전파하지 않는다")
	@Test
	void clear_whenDataAccessException_doesNotThrow() {
		// given
		willThrow(new QueryTimeoutException("timeout")).given(redisTemplate).delete(anyString());

		// when & then
		assertThatCode(() -> store.clear(1L, "idem-key")).doesNotThrowAnyException();
	}
}
