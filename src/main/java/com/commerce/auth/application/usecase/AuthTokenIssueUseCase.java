package com.commerce.auth.application.usecase;

import org.springframework.stereotype.Component;

import com.commerce.auth.application.port.RefreshTokenStore;
import com.commerce.auth.application.port.TokenIssuer;
import com.commerce.auth.application.port.vo.TokenClaims;
import com.commerce.auth.application.result.AuthTokenIssueResult;
import com.commerce.member.domain.Member;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AuthTokenIssueUseCase {

	private final TokenIssuer tokenIssuer;
	private final RefreshTokenStore refreshTokenStore;

	public AuthTokenIssueResult issue(Member member) {
		TokenClaims claims = TokenClaims.of(member.getId(), member.getRole());

		String accessToken = tokenIssuer.createAccessToken(claims);
		String refreshToken = tokenIssuer.createRefreshToken(claims);

		refreshTokenStore.save(member.getId(), refreshToken);

		return AuthTokenIssueResult.of(accessToken, refreshToken);
	}
}
