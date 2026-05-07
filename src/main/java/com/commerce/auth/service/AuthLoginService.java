package com.commerce.auth.service;

import java.time.Duration;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.auth.exception.AuthErrorCode;
import com.commerce.auth.exception.AuthException;
import com.commerce.auth.jwt.JwtProperties;
import com.commerce.auth.jwt.JwtTokenClaims;
import com.commerce.auth.jwt.JwtTokenProvider;
import com.commerce.auth.jwt.JwtTokenType;
import com.commerce.auth.redis.RefreshTokenStore;
import com.commerce.auth.service.command.AuthLoginCommand;
import com.commerce.auth.service.result.AuthLoginResult;
import com.commerce.auth.util.PasswordHasher;
import com.commerce.member.application.MemberQueryService;
import com.commerce.member.domain.Member;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthLoginService {

	private final MemberQueryService memberQueryService;
	private final JwtTokenProvider jwtTokenProvider;
	private final PasswordHasher passwordHasher;
	private final RefreshTokenStore refreshTokenStore;
	private final JwtProperties jwtProperties;

	public AuthLoginResult login(AuthLoginCommand command) {
		Member member = memberQueryService.findByEmail(command.getEmail())
			.orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_CREDENTIALS));

		if (!passwordHasher.matches(command.getPassword(), member.getPassword())) {
			throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS);
		}

		JwtTokenClaims accessTokenClaims = JwtTokenClaims.from(member, JwtTokenType.ACCESS_TOKEN);
		JwtTokenClaims refreshTokenClaims = JwtTokenClaims.from(member, JwtTokenType.REFRESH_TOKEN);
		String accessToken = jwtTokenProvider.createAccessToken(accessTokenClaims);
		String refreshToken = jwtTokenProvider.createRefreshToken(refreshTokenClaims);

		storeRefreshToken(member.getId(), refreshToken);

		return AuthLoginResult.from(member, accessToken, refreshToken);
	}

	private void storeRefreshToken(Long memberId, String refreshToken) {
		Duration ttl = Duration.ofMillis(jwtProperties.getRefreshExpiration());
		refreshTokenStore.save(memberId, refreshToken, ttl);
	}
}
