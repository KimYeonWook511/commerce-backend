package com.commerce.auth.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.auth.exception.AuthErrorCode;
import com.commerce.auth.exception.AuthException;
import com.commerce.auth.application.command.AuthLoginCommand;
import com.commerce.auth.application.result.AuthLoginResult;
import com.commerce.auth.application.result.AuthTokenIssueResult;
import com.commerce.auth.infrastructure.PasswordHasher;
import com.commerce.member.application.MemberQueryService;
import com.commerce.member.domain.Member;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthLoginService {

	private final MemberQueryService memberQueryService;
	private final AuthTokenIssueService authTokenIssueService;
	private final PasswordHasher passwordHasher;

	public AuthLoginResult login(AuthLoginCommand command) {
		Member member = memberQueryService.findByEmail(command.getEmail())
			.orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_CREDENTIALS));

		if (!passwordHasher.matches(command.getPassword(), member.getPassword())) {
			throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS);
		}

		AuthTokenIssueResult tokenIssueResult = authTokenIssueService.issue(member);

		return AuthLoginResult.from(
			member,
			tokenIssueResult.getAccessToken(),
			tokenIssueResult.getRefreshToken()
		);
	}
}
