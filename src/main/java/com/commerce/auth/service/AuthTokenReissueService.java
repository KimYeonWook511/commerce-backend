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
import com.commerce.auth.jwt.JwtTokenValidator;
import com.commerce.auth.redis.RefreshTokenStore;
import com.commerce.auth.service.command.AuthTokenReissueCommand;
import com.commerce.auth.service.result.AuthTokenReissueResult;
import com.commerce.member.application.MemberQueryService;
import com.commerce.member.domain.Member;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthTokenReissueService {

	private final MemberQueryService memberQueryService;
	private final JwtTokenProvider jwtTokenProvider;
	private final JwtTokenValidator jwtTokenValidator;
	private final RefreshTokenStore refreshTokenStore;
	private final JwtProperties jwtProperties;

	public AuthTokenReissueResult reissue(AuthTokenReissueCommand command) {
		String refreshToken = command.getRefreshToken();
		Claims claims = jwtTokenValidator.validateRefreshToken(refreshToken);

		validateRefreshTokenType(claims);

		Long memberId = parseMemberId(claims.getSubject());

		validateStoredRefreshToken(memberId, refreshToken);

		Member member = memberQueryService.findById(memberId)
			.orElseThrow(() -> new AuthException(AuthErrorCode.TOKEN_INVALID));

		JwtTokenClaims accessTokenClaims = JwtTokenClaims.from(member, JwtTokenType.ACCESS_TOKEN);
		JwtTokenClaims refreshTokenClaims = JwtTokenClaims.from(member, JwtTokenType.REFRESH_TOKEN);
		String newAccessToken = jwtTokenProvider.createAccessToken(accessTokenClaims);
		String newRefreshToken = jwtTokenProvider.createRefreshToken(refreshTokenClaims);

		storeRefreshToken(memberId, newRefreshToken);

		return AuthTokenReissueResult.of(newAccessToken, newRefreshToken);
	}

	private void validateRefreshTokenType(Claims claims) {
		String tokenType = claims.get("type", String.class);

		if (!JwtTokenType.REFRESH_TOKEN.name().equals(tokenType)) {
			throw new AuthException(AuthErrorCode.TOKEN_INVALID);
		}
	}

	private Long parseMemberId(String subject) {
		try {
			return Long.parseLong(subject);
		} catch (NumberFormatException ex) {
			throw new AuthException(AuthErrorCode.TOKEN_INVALID);
		}
	}

	private void validateStoredRefreshToken(Long memberId, String refreshToken) {
		String storedRefreshToken = refreshTokenStore.get(memberId)
			.orElseThrow(() -> new AuthException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));

		if (!storedRefreshToken.equals(refreshToken)) {
			throw new AuthException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
		}
	}

	private void storeRefreshToken(Long memberId, String refreshToken) {
		Duration ttl = Duration.ofMillis(jwtProperties.getRefreshExpiration());
		refreshTokenStore.save(memberId, refreshToken, ttl);
	}
}
