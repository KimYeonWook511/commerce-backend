package com.commerce.auth.application.usecase;

import org.springframework.stereotype.Component;

import com.commerce.auth.application.command.AuthSignUpCommand;
import com.commerce.auth.application.result.AuthSignUpResult;
import com.commerce.auth.application.result.AuthTokenIssueResult;
import com.commerce.auth.application.port.PasswordHasher;
import com.commerce.member.application.service.MemberRegistrationService;
import com.commerce.member.application.command.MemberRegistrationCommand;
import com.commerce.member.domain.Member;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthSignUpUseCase {

	private final MemberRegistrationService memberRegistrationService;
	private final AuthTokenIssueUseCase authTokenIssueService;
	private final PasswordHasher passwordHasher;

	public AuthSignUpResult signUp(AuthSignUpCommand command) {
		Member member = memberRegistrationService.register(MemberRegistrationCommand.builder()
			.email(command.getEmail())
			.passwordHash(passwordHasher.hash(command.getPassword()))
			.username(command.getUsername())
			.build());

		AuthTokenIssueResult tokenIssueResult = authTokenIssueService.issue(member);

		log.info("회원 가입 성공 memberId={}", member.getId());
		return AuthSignUpResult.from(
			member,
			tokenIssueResult.getAccessToken(),
			tokenIssueResult.getRefreshToken()
		);
	}
}
