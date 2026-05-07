package com.commerce.auth.service;

import java.time.Duration;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.auth.jwt.JwtProperties;
import com.commerce.auth.jwt.JwtTokenClaims;
import com.commerce.auth.jwt.JwtTokenProvider;
import com.commerce.auth.jwt.JwtTokenType;
import com.commerce.auth.redis.RefreshTokenStore;
import com.commerce.auth.service.command.AuthSignUpCommand;
import com.commerce.auth.service.result.AuthSignUpResult;
import com.commerce.auth.util.PasswordHasher;
import com.commerce.member.application.MemberRegistrationService;
import com.commerce.member.application.command.MemberRegistrationCommand;
import com.commerce.member.domain.Member;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthSignUpService {

	private final MemberRegistrationService memberRegistrationService;
	private final JwtTokenProvider jwtTokenProvider;
	private final PasswordHasher passwordHasher;
	private final RefreshTokenStore refreshTokenStore;
	private final JwtProperties jwtProperties;

	@Transactional
	public AuthSignUpResult signUp(AuthSignUpCommand command) {
		Member member = memberRegistrationService.register(MemberRegistrationCommand.builder()
			.email(command.getEmail())
			.passwordHash(passwordHasher.hash(command.getPassword()))
			.username(command.getUsername())
			.build());

		JwtTokenClaims accessTokenClaims = JwtTokenClaims.from(member, JwtTokenType.ACCESS_TOKEN);
		JwtTokenClaims refreshTokenClaims = JwtTokenClaims.from(member, JwtTokenType.REFRESH_TOKEN);
		String accessToken = jwtTokenProvider.createAccessToken(accessTokenClaims);
		String refreshToken = jwtTokenProvider.createRefreshToken(refreshTokenClaims);

		storeRefreshToken(member.getId(), refreshToken);

		return AuthSignUpResult.from(member, accessToken, refreshToken);
	}

	private void storeRefreshToken(Long memberId, String refreshToken) {
		Duration ttl = Duration.ofMillis(jwtProperties.getRefreshExpiration());
		refreshTokenStore.save(memberId, refreshToken, ttl);
	}
}
