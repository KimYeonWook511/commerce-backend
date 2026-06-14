package com.commerce.auth.application.usecase;

import org.springframework.stereotype.Component;

import com.commerce.auth.domain.exception.AuthErrorCode;
import com.commerce.auth.domain.exception.AuthException;
import com.commerce.auth.application.dto.AuthLoginCommand;
import com.commerce.auth.application.dto.AuthLoginResult;
import com.commerce.auth.application.dto.AuthTokenIssueResult;
import com.commerce.auth.application.port.PasswordHasher;
import com.commerce.member.application.service.MemberQueryService;
import com.commerce.member.domain.Member;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthLoginUseCase {

	private final MemberQueryService memberQueryService;
	private final AuthTokenIssueUseCase authTokenIssueUseCase;
	private final PasswordHasher passwordHasher;

	public AuthLoginResult login(AuthLoginCommand command) {
		Member member = memberQueryService.findByEmail(command.getEmail())
			.orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_CREDENTIALS));

		if (!passwordHasher.matches(command.getPassword(), member.getPassword())) {
			throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS);
		}

		AuthTokenIssueResult tokenIssueResult = authTokenIssueUseCase.issue(member);

		log.info("로그인 성공 memberId={}", member.getId());
		return AuthLoginResult.from(
			member,
			tokenIssueResult.getAccessToken(),
			tokenIssueResult.getRefreshToken()
		);
	}
}
