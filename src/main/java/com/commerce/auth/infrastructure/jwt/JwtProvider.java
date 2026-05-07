package com.commerce.auth.infrastructure.jwt;

import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class JwtProvider {

	private final JwtProperties jwtProperties;

	public String createAccessToken(JwtClaims claims) {
		return createToken(claims, jwtProperties.getAccessSecretKey(), jwtProperties.getAccessExpiration());
	}

	public String createRefreshToken(JwtClaims claims) {
		return createToken(claims, jwtProperties.getRefreshSecretKey(), jwtProperties.getRefreshExpiration());
	}

	private String createToken(JwtClaims claims, SecretKey secretKey, Long expiration) {
		Instant now = Instant.now();
		Instant expiredAt = now.plusMillis(expiration);

		return Jwts.builder()
			.setSubject(claims.getMemberId().toString()) // 커스텀 클레임으로 넣어도 되지만 sub에 넣는게 정석
			// .claim("memberId", tokenPayload.getMemberId())
			.claim("role", claims.getMemberRole())
			.claim("type", claims.getTokenType())
			.setIssuedAt(Date.from(now))
			.setExpiration(Date.from(expiredAt))
			.signWith(secretKey) // 키만 넘기면 알고리즘은 키가 지원하는 알고리즘으로 자동 선택됨
			.compact();
	}

}
