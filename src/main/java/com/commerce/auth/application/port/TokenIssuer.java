package com.commerce.auth.application.port;

import com.commerce.auth.application.port.vo.TokenClaims;

public interface TokenIssuer {

	String createAccessToken(TokenClaims claims);

	String createRefreshToken(TokenClaims claims);
}
