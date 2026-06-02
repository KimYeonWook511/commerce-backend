package com.commerce.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import com.commerce.auth.exception.RefreshTokenStoreUnavailableException;
import com.commerce.auth.infrastructure.jwt.JwtProperties;

@ExtendWith(MockitoExtension.class)
class RedisRefreshTokenStoreTest {

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	@Mock
	private JwtProperties jwtProperties;

	private RedisRefreshTokenStore store;

	@BeforeEach
	void setUp() {
		store = new RedisRefreshTokenStore(redisTemplate, jwtProperties);
	}

	@DisplayName("save 정상 — refresh:{memberId} key로 값을 저장한다")
	@Test
	void save_whenSuccess_storesWithCorrectKey() {
		// given
		given(jwtProperties.getRefreshExpiration()).willReturn(3600000L);
		given(redisTemplate.opsForValue()).willReturn(valueOperations);

		// when
		store.save(1L, "refresh-token");

		// then
		then(valueOperations).should().set(eq("refresh:1"), eq("refresh-token"), any(Duration.class));
	}

	@DisplayName("save 시 DataAccessException이 발생하면 RefreshTokenStoreUnavailableException으로 변환한다")
	@Test
	void save_whenDataAccessException_throwsStoreUnavailable() {
		// given
		given(jwtProperties.getRefreshExpiration()).willReturn(3600000L);
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		willThrow(new QueryTimeoutException("timeout")).given(valueOperations).set(any(), any(), any(Duration.class));

		// when & then
		assertThatThrownBy(() -> store.save(1L, "refresh-token"))
			.isInstanceOf(RefreshTokenStoreUnavailableException.class)
			.hasCauseInstanceOf(QueryTimeoutException.class);
	}

	@DisplayName("get 정상 — 저장된 값을 Optional로 반환한다")
	@Test
	void get_whenSuccess_returnsValue() {
		// given
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.get("refresh:1")).willReturn("refresh-token");

		// when
		var result = store.get(1L);

		// then
		assertThat(result).isPresent().contains("refresh-token");
	}

	@DisplayName("get 시 DataAccessException이 발생하면 RefreshTokenStoreUnavailableException으로 변환한다")
	@Test
	void get_whenDataAccessException_throwsStoreUnavailable() {
		// given
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.get("refresh:1")).willThrow(new QueryTimeoutException("timeout"));

		// when & then
		assertThatThrownBy(() -> store.get(1L))
			.isInstanceOf(RefreshTokenStoreUnavailableException.class)
			.hasCauseInstanceOf(QueryTimeoutException.class);
	}
}
