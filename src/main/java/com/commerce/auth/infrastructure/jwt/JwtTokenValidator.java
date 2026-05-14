package com.commerce.auth.infrastructure.jwt;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import com.commerce.auth.application.port.TokenValidator;
import com.commerce.auth.application.port.vo.ParsedTokenClaims;
import com.commerce.auth.exception.AuthErrorCode;
import com.commerce.auth.exception.AuthException;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenValidator implements TokenValidator {

	private final JwtProperties jwtProperties;

	@Override
	public ParsedTokenClaims validateAccessToken(String token) {
		Claims claims = validate(token, jwtProperties.getAccessSecretKey());
		validateTokenType(claims, JwtType.ACCESS_TOKEN);
		return ParsedTokenClaims.of(claims.getSubject(), claims.get("role", String.class));
	}

	@Override
	public ParsedTokenClaims validateRefreshToken(String token) {
		Claims claims = validate(token, jwtProperties.getRefreshSecretKey());
		validateTokenType(claims, JwtType.REFRESH_TOKEN);
		return ParsedTokenClaims.of(claims.getSubject(), claims.get("role", String.class));
	}

	private Claims validate(String token, SecretKey secretKey) {
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

	private void validateTokenType(Claims claims, JwtType expectedType) {
		String tokenType = claims.get("type", String.class);

		if (!expectedType.name().equals(tokenType)) {
			throw new AuthException(AuthErrorCode.TOKEN_INVALID);
		}
	}

}
