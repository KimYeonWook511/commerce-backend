package com.commerce.auth.infrastructure.jwt;

import org.springframework.stereotype.Component;

import com.commerce.auth.application.port.RefreshTokenValidator;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;

/**
 * refresh 토큰을 검증해 회원 id를 반환하는 JWT 어댑터. RefreshTokenValidator port를 구현한다.
 */
@Component
@RequiredArgsConstructor
public class JwtRefreshTokenValidator implements RefreshTokenValidator {

	private final JwtProperties jwtProperties;
	private final JwtClaimsReader jwtClaimsReader;

	@Override
	public Long validateRefreshToken(String refreshToken) {
		Claims claims = jwtClaimsReader.read(refreshToken, jwtProperties.getRefreshSecretKey(), JwtType.REFRESH_TOKEN);
		return jwtClaimsReader.parseMemberId(claims.getSubject());
	}
}
