package com.commerce.auth.application;

import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.auth.application.command.AuthTokenReissueCommand;
import com.commerce.auth.application.port.RefreshTokenStore;
import com.commerce.auth.application.port.TokenValidator;
import com.commerce.auth.application.port.vo.ParsedTokenClaims;
import com.commerce.auth.application.result.AuthTokenIssueResult;
import com.commerce.auth.application.result.AuthTokenReissueResult;
import com.commerce.auth.exception.AuthErrorCode;
import com.commerce.auth.exception.AuthException;
import com.commerce.member.application.MemberQueryService;
import com.commerce.member.domain.Member;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthTokenReissueService {

	private final MemberQueryService memberQueryService;
	private final AuthTokenIssueService authTokenIssueService;
	private final TokenValidator tokenValidator;
	private final RefreshTokenStore refreshTokenStore;

	public AuthTokenReissueResult reissue(AuthTokenReissueCommand command) {
		String refreshToken = command.getRefreshToken();
		ParsedTokenClaims claims = tokenValidator.validateRefreshToken(refreshToken);

		Long memberId = parseMemberId(claims.subject());

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
		Optional<String> stored;
		try {
			stored = refreshTokenStore.get(memberId);
		} catch (DataAccessException e) {
			log.error("refresh token 조회 실패: memberId={}", memberId, e);
			throw new AuthException(AuthErrorCode.INTERNAL_ERROR);
		}

		String storedRefreshToken = stored
			.orElseThrow(() -> new AuthException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));

		if (!storedRefreshToken.equals(refreshToken)) {
			throw new AuthException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
		}
	}

}
