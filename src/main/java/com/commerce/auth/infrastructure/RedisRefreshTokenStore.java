package com.commerce.auth.infrastructure;

import java.time.Duration;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.commerce.auth.application.port.RefreshTokenStore;
import com.commerce.auth.exception.RefreshTokenStoreUnavailableException;
import com.commerce.auth.infrastructure.jwt.JwtProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRefreshTokenStore implements RefreshTokenStore {

	private final StringRedisTemplate redisTemplate;
	private final JwtProperties jwtProperties;

	// 동일 key(refresh:{memberId})를 덮어쓰므로 기존 refresh token이 무효화된다
	@Override
	public void save(Long memberId, String refreshToken) {
		Duration ttl = Duration.ofMillis(jwtProperties.getRefreshExpiration());
		try {
			redisTemplate.opsForValue().set(buildKey(memberId), refreshToken, ttl);
		} catch (DataAccessException e) {
			log.error("refresh token 저장 실패: memberId={}", memberId, e);
			throw new RefreshTokenStoreUnavailableException(e);
		}
	}

	@Override
	public Optional<String> get(Long memberId) {
		try {
			return Optional.ofNullable(redisTemplate.opsForValue().get(buildKey(memberId)));
		} catch (DataAccessException e) {
			log.error("refresh token 조회 실패: memberId={}", memberId, e);
			throw new RefreshTokenStoreUnavailableException(e);
		}
	}

	private String buildKey(Long memberId) {
		return "refresh:" + memberId;
	}

}
