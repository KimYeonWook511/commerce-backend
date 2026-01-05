package com.commerce.auth.redis;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

	private final StringRedisTemplate redisTemplate;

	public void save(Long memberId, String refreshToken, Duration ttl) {
		redisTemplate.opsForValue().set(buildKey(memberId), refreshToken, ttl);
	}

	public Optional<String> get(Long memberId) {
		return Optional.ofNullable(redisTemplate.opsForValue().get(buildKey(memberId)));
	}

	public void delete(Long memberId) {
		redisTemplate.delete(buildKey(memberId));
	}

	private String buildKey(Long memberId) {
		return "refresh:" + memberId;
	}

}
