package com.commerce.auth.application;

import org.springframework.stereotype.Service;

import com.commerce.auth.exception.AuthErrorCode;
import com.commerce.auth.exception.AuthException;
import com.commerce.auth.application.result.TokenAuthenticationResult;
import com.commerce.auth.infrastructure.jwt.JwtValidator;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TokenAuthenticationService {

	private final JwtValidator jwtValidator;

	public TokenAuthenticationResult authenticateAccessToken(String accessToken) {
		Claims claims = jwtValidator.validateAccessToken(accessToken);
		Long memberId = parseMemberId(claims.getSubject());
		String role = claims.get("role", String.class);

		return TokenAuthenticationResult.of(memberId, role);
	}

	private Long parseMemberId(String subject) {
		try {
			return Long.parseLong(subject);
		} catch (NumberFormatException ex) {
			throw new AuthException(AuthErrorCode.TOKEN_INVALID);
		}
	}
}
