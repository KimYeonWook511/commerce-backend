package com.commerce.auth.application;

import java.time.Duration;

import org.springframework.stereotype.Service;

import com.commerce.auth.application.result.AuthTokenIssueResult;
import com.commerce.auth.infrastructure.RefreshTokenStore;
import com.commerce.auth.infrastructure.jwt.JwtProperties;
import com.commerce.auth.infrastructure.jwt.JwtClaims;
import com.commerce.auth.infrastructure.jwt.JwtProvider;
import com.commerce.auth.infrastructure.jwt.JwtType;
import com.commerce.member.domain.Member;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthTokenIssueService {

	private final JwtProvider jwtProvider;
	private final RefreshTokenStore refreshTokenStore;
	private final JwtProperties jwtProperties;

	public AuthTokenIssueResult issue(Member member) {
		JwtClaims accessTokenClaims = JwtClaims.of(
			member.getId(),
			member.getRole(),
			JwtType.ACCESS_TOKEN
		);
		JwtClaims refreshTokenClaims = JwtClaims.of(
			member.getId(),
			member.getRole(),
			JwtType.REFRESH_TOKEN
		);

		String accessToken = jwtProvider.createAccessToken(accessTokenClaims);
		String refreshToken = jwtProvider.createRefreshToken(refreshTokenClaims);

		storeRefreshToken(member.getId(), refreshToken);

		return AuthTokenIssueResult.of(accessToken, refreshToken);
	}

	private void storeRefreshToken(Long memberId, String refreshToken) {
		Duration ttl = Duration.ofMillis(jwtProperties.getRefreshExpiration());
		refreshTokenStore.save(memberId, refreshToken, ttl);
	}
}
