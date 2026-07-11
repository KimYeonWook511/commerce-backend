package com.commerce.auth.application.usecase;

import org.springframework.stereotype.Component;

import com.commerce.auth.application.dto.AuthTokenReissueCommand;
import com.commerce.auth.application.port.RefreshTokenStore;
import com.commerce.auth.application.port.RefreshTokenValidator;
import com.commerce.auth.application.dto.AuthTokenIssueResult;
import com.commerce.auth.application.dto.AuthTokenReissueResult;
import com.commerce.auth.domain.exception.AuthErrorCode;
import com.commerce.auth.domain.exception.AuthException;
import com.commerce.member.application.service.FindMemberService;
import com.commerce.member.domain.Member;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AuthTokenReissueUseCase {

	private final FindMemberService findMemberService;
	private final AuthTokenIssueUseCase authTokenIssueUseCase;
	private final RefreshTokenValidator refreshTokenValidator;
	private final RefreshTokenStore refreshTokenStore;

	public AuthTokenReissueResult reissue(AuthTokenReissueCommand command) {
		String refreshToken = command.getRefreshToken();
		Long memberId = refreshTokenValidator.validateRefreshToken(refreshToken);

		validateStoredRefreshToken(memberId, refreshToken);

		Member member = findMemberService.findById(memberId)
			.orElseThrow(() -> new AuthException(AuthErrorCode.TOKEN_INVALID));

		AuthTokenIssueResult tokenIssueResult = authTokenIssueUseCase.issue(member);

		return AuthTokenReissueResult.of(
			tokenIssueResult.getAccessToken(),
			tokenIssueResult.getRefreshToken()
		);
	}

	private void validateStoredRefreshToken(Long memberId, String refreshToken) {
		String storedRefreshToken = refreshTokenStore.get(memberId)
			.orElseThrow(() -> new AuthException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));

		if (!storedRefreshToken.equals(refreshToken)) {
			throw new AuthException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
		}
	}

}
