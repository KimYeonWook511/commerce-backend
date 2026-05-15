package com.commerce.auth.application;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.commerce.auth.application.port.RefreshTokenStore;
import com.commerce.auth.application.port.TokenIssuer;
import com.commerce.auth.application.port.vo.TokenClaims;
import com.commerce.auth.application.result.AuthTokenIssueResult;
import com.commerce.auth.exception.AuthErrorCode;
import com.commerce.auth.exception.AuthException;
import com.commerce.member.domain.Member;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthTokenIssueService {

	private final TokenIssuer tokenIssuer;
	private final RefreshTokenStore refreshTokenStore;

	public AuthTokenIssueResult issue(Member member) {
		TokenClaims claims = TokenClaims.of(member.getId(), member.getRole());

		String accessToken = tokenIssuer.createAccessToken(claims);
		String refreshToken = tokenIssuer.createRefreshToken(claims);

		try {
			refreshTokenStore.save(member.getId(), refreshToken);
		} catch (DataAccessException e) {
			log.error("refresh token Redis 저장 실패 memberId={}", member.getId(), e);
			throw new AuthException(AuthErrorCode.INTERNAL_ERROR);
		}

		return AuthTokenIssueResult.of(accessToken, refreshToken);
	}
}
