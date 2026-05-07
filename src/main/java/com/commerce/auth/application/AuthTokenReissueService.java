package com.commerce.auth.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.auth.exception.AuthErrorCode;
import com.commerce.auth.exception.AuthException;
import com.commerce.auth.infrastructure.jwt.JwtValidator;
import com.commerce.auth.infrastructure.RefreshTokenStore;
import com.commerce.auth.application.command.AuthTokenReissueCommand;
import com.commerce.auth.application.result.AuthTokenIssueResult;
import com.commerce.auth.application.result.AuthTokenReissueResult;
import com.commerce.member.application.MemberQueryService;
import com.commerce.member.domain.Member;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthTokenReissueService {

	private final MemberQueryService memberQueryService;
	private final AuthTokenIssueService authTokenIssueService;
	private final JwtValidator jwtValidator;
	private final RefreshTokenStore refreshTokenStore;

	public AuthTokenReissueResult reissue(AuthTokenReissueCommand command) {
		String refreshToken = command.getRefreshToken();
		Claims claims = jwtValidator.validateRefreshToken(refreshToken);

		Long memberId = parseMemberId(claims.getSubject());

		validateStoredRefreshToken(memberId, refreshToken);

		Member member = memberQueryService.findById(memberId)
			.orElseThrow(() -> new AuthException(AuthErrorCode.TOKEN_INVALID));

		AuthTokenIssueResult tokenIssueResult = authTokenIssueService.issue(member);

		return AuthTokenReissueResult.of(
			tokenIssueResult.getAccessToken(),
			tokenIssueResult.getRefreshToken()
		);
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

}
