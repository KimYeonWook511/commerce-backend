package com.commerce.auth.application;

import org.springframework.stereotype.Service;

import com.commerce.auth.application.port.TokenValidator;
import com.commerce.auth.application.port.vo.ParsedTokenClaims;
import com.commerce.auth.application.result.TokenAuthenticationResult;
import com.commerce.auth.exception.AuthErrorCode;
import com.commerce.auth.exception.AuthException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TokenAuthenticationService {

	private final TokenValidator tokenValidator;

	public TokenAuthenticationResult authenticateAccessToken(String accessToken) {
		ParsedTokenClaims claims = tokenValidator.validateAccessToken(accessToken);
		Long memberId = parseMemberId(claims.subject());
		String role = claims.role();

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
