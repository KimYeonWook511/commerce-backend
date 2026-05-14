package com.commerce.auth.infrastructure.jwt;

import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import com.commerce.auth.application.port.TokenIssuer;
import com.commerce.auth.application.port.vo.TokenClaims;

import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class JwtTokenIssuer implements TokenIssuer {

	private final JwtProperties jwtProperties;

	@Override
	public String createAccessToken(TokenClaims claims) {
		return createToken(claims, jwtProperties.getAccessSecretKey(), jwtProperties.getAccessExpiration(),
			JwtType.ACCESS_TOKEN);
	}

	@Override
	public String createRefreshToken(TokenClaims claims) {
		return createToken(claims, jwtProperties.getRefreshSecretKey(), jwtProperties.getRefreshExpiration(),
			JwtType.REFRESH_TOKEN);
	}

	private String createToken(TokenClaims claims, SecretKey secretKey, Long expiration, JwtType tokenType) {
		Instant now = Instant.now();
		Instant expiredAt = now.plusMillis(expiration);

		return Jwts.builder()
			.setSubject(claims.memberId().toString()) // 커스텀 클레임으로 넣어도 되지만 sub에 넣는게 정석
			// .claim("memberId", tokenPayload.getMemberId())
			.claim("role", claims.memberRole())
			.claim("type", tokenType)
			.setIssuedAt(Date.from(now))
			.setExpiration(Date.from(expiredAt))
			.signWith(secretKey) // 키만 넘기면 알고리즘은 키가 지원하는 알고리즘으로 자동 선택됨
			.compact();
	}

}
