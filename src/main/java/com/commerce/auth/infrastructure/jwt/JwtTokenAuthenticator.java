package com.commerce.auth.infrastructure.jwt;

import org.springframework.stereotype.Component;

import com.commerce.auth.domain.exception.AuthErrorCode;
import com.commerce.auth.domain.exception.AuthException;
import com.commerce.common.security.Role;
import com.commerce.common.security.context.AuthenticationContext;
import com.commerce.common.security.port.TokenAuthenticator;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;

/**
 * access 토큰을 검증해 인증 신원(AuthenticationContext)을 반환하는 JWT 어댑터.
 * security의 TokenAuthenticator port를 구현한다(auth → common 한 방향, security는 JWT를 모른다).
 */
@Component
@RequiredArgsConstructor
public class JwtTokenAuthenticator implements TokenAuthenticator {

	private final JwtProperties jwtProperties;
	private final JwtClaimsReader jwtClaimsReader;

	@Override
	public AuthenticationContext authenticate(String accessToken) {
		Claims claims = jwtClaimsReader.read(accessToken, jwtProperties.getAccessSecretKey(), JwtType.ACCESS_TOKEN);
		Long memberId = jwtClaimsReader.parseMemberId(claims.getSubject());
		Role role = parseRole(claims.get("role", String.class));
		return new AuthenticationContext(memberId, role);
	}

	private Role parseRole(String role) {
		try {
			return Role.valueOf(role);
		} catch (IllegalArgumentException | NullPointerException ex) {
			throw new AuthException(AuthErrorCode.TOKEN_INVALID);
		}
	}
}
