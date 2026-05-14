package com.commerce.auth.application.port;

import com.commerce.auth.application.port.vo.ParsedTokenClaims;

public interface TokenValidator {

	ParsedTokenClaims validateAccessToken(String token);

	ParsedTokenClaims validateRefreshToken(String token);
}
