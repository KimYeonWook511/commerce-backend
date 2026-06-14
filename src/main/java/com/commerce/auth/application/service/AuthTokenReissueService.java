package com.commerce.auth.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.auth.application.dto.AuthTokenReissueCommand;
import com.commerce.auth.application.port.RefreshTokenStore;
import com.commerce.auth.application.usecase.AuthTokenIssueUseCase;
import com.commerce.auth.application.port.TokenValidator;
import com.commerce.auth.application.port.vo.ParsedTokenClaims;
import com.commerce.auth.application.dto.AuthTokenIssueResult;
import com.commerce.auth.application.dto.AuthTokenReissueResult;
import com.commerce.auth.domain.exception.AuthErrorCode;
import com.commerce.auth.domain.exception.AuthException;
import com.commerce.member.application.service.MemberQueryService;
import com.commerce.member.domain.Member;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthTokenReissueService {

	private final MemberQueryService memberQueryService;
	private final AuthTokenIssueUseCase authTokenIssueUseCase;
	private final TokenValidator tokenValidator;
	private final RefreshTokenStore refreshTokenStore;

	@Transactional(readOnly = true)
	public AuthTokenReissueResult reissue(AuthTokenReissueCommand command) {
		String refreshToken = command.getRefreshToken();
		ParsedTokenClaims claims = tokenValidator.validateRefreshToken(refreshToken);

		Long memberId = parseMemberId(claims.subject());

		validateStoredRefreshToken(memberId, refreshToken);

		Member member = memberQueryService.findById(memberId)
			.orElseThrow(() -> new AuthException(AuthErrorCode.TOKEN_INVALID));

		AuthTokenIssueResult tokenIssueResult = authTokenIssueUseCase.issue(member);

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
