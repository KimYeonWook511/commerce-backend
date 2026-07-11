package com.commerce.auth.infrastructure.jwt;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import com.commerce.auth.domain.exception.AuthErrorCode;
import com.commerce.auth.domain.exception.AuthException;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import lombok.extern.slf4j.Slf4j;

/**
 * JWT 서명·만료·형식·타입을 검증하고 Claims를 추출하는 공통 파싱기.
 * access·refresh 어댑터가 공유해 파싱·예외 매핑 중복을 없앤다.
 */
@Slf4j
@Component
class JwtClaimsReader {

	Claims read(String token, SecretKey secretKey, JwtType expectedType) {
		Claims claims = parse(token, secretKey);
		validateType(claims, expectedType);
		return claims;
	}

	Long parseMemberId(String subject) {
		try {
			return Long.parseLong(subject);
		} catch (NumberFormatException ex) {
			throw new AuthException(AuthErrorCode.TOKEN_INVALID);
		}
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
		} catch (UnsupportedJwtException | MalformedJwtException | SecurityException e) {
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
}
