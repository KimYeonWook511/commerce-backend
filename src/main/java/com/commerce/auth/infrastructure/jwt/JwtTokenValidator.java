package com.commerce.auth.infrastructure.jwt;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import com.commerce.auth.application.port.RefreshTokenValidator;
import com.commerce.auth.domain.exception.AuthErrorCode;
import com.commerce.auth.domain.exception.AuthException;
import com.commerce.common.security.Role;
import com.commerce.common.security.context.AuthenticationContext;
import com.commerce.common.security.port.TokenValidator;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * JWT 토큰을 검증하는 어댑터. access 토큰은 인증 신원으로(common.security의 TokenValidator),
 * refresh 토큰은 회원 id로(RefreshTokenValidator) 해석한다. 두 포트가 하나의 JWT 파싱 기술을 공유하므로
 * 한 어댑터가 구현한다. security의 TokenValidator를 구현해도 방향은 auth → common 한 방향(security는 JWT를 모른다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenValidator implements TokenValidator, RefreshTokenValidator {

	private final JwtProperties jwtProperties;

	@Override
	public AuthenticationContext validate(String accessToken) {
		Claims claims = read(accessToken, jwtProperties.getAccessSecretKey(), JwtType.ACCESS_TOKEN);
		Long memberId = parseMemberId(claims.getSubject());
		Role role = parseRole(claims.get("role", String.class));
		return new AuthenticationContext(memberId, role);
	}

	@Override
	public Long validateRefreshToken(String refreshToken) {
		Claims claims = read(refreshToken, jwtProperties.getRefreshSecretKey(), JwtType.REFRESH_TOKEN);
		return parseMemberId(claims.getSubject());
	}

	private Claims read(String token, SecretKey secretKey, JwtType expectedType) {
		Claims claims = parse(token, secretKey);
		validateType(claims, expectedType);
		return claims;
	}

	private Claims parse(String token, SecretKey secretKey) {
		try {
			return Jwts.parserBuilder()
				.setSigningKey(secretKey)
				.build()
				.parseClaimsJws(token)
				.getBody();
		} catch (ExpiredJwtException e) {
			log.warn("JWT expired");
			throw new AuthException(AuthErrorCode.TOKEN_EXPIRED);
		} catch (JwtException e) {
			// 서명 위조·형식 오류·미지원 타입 등 나머지 JWT 오류(jjwt의 SignatureException 포함).
			// 만료는 위에서 먼저 잡는다.
			log.warn("Invalid JWT");
			throw new AuthException(AuthErrorCode.TOKEN_INVALID);
		} catch (IllegalArgumentException e) {
			log.warn("Empty or null JWT");
			throw new AuthException(AuthErrorCode.TOKEN_EMPTY);
		}
	}

	private void validateType(Claims claims, JwtType expectedType) {
		String tokenType = claims.get("type", String.class);
		if (!expectedType.name().equals(tokenType)) {
			throw new AuthException(AuthErrorCode.TOKEN_INVALID);
		}
	}

	private Long parseMemberId(String subject) {
		try {
			return Long.parseLong(subject);
		} catch (NumberFormatException ex) {
			throw new AuthException(AuthErrorCode.TOKEN_INVALID);
		}
	}

	private Role parseRole(String role) {
		try {
			return Role.valueOf(role);
		} catch (IllegalArgumentException | NullPointerException ex) {
			throw new AuthException(AuthErrorCode.TOKEN_INVALID);
		}
	}
}
