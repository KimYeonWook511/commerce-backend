package com.commerce.auth.infrastructure;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.commerce.auth.application.port.RefreshTokenStore;
import com.commerce.auth.infrastructure.jwt.JwtProperties;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RedisRefreshTokenStore implements RefreshTokenStore {

	private final StringRedisTemplate redisTemplate;
	private final JwtProperties jwtProperties;

	// 동일 key(refresh:{memberId})를 덮어쓰므로 기존 refresh token이 무효화된다
	@Override
	public void save(Long memberId, String refreshToken) {
		Duration ttl = Duration.ofMillis(jwtProperties.getRefreshExpiration());
		redisTemplate.opsForValue().set(buildKey(memberId), refreshToken, ttl);
	}

	@Override
	public Optional<String> get(Long memberId) {
		return Optional.ofNullable(redisTemplate.opsForValue().get(buildKey(memberId)));
	}

	private String buildKey(Long memberId) {
		return "refresh:" + memberId;
	}

}
