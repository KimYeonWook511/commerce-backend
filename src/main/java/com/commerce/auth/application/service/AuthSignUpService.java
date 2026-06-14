package com.commerce.auth.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.auth.application.command.AuthSignUpCommand;
import com.commerce.auth.application.result.AuthSignUpResult;
import com.commerce.auth.application.result.AuthTokenIssueResult;
import com.commerce.auth.application.port.PasswordHasher;
import com.commerce.auth.application.usecase.AuthTokenIssueUseCase;
import com.commerce.member.application.service.MemberRegistrationService;
import com.commerce.member.application.command.MemberRegistrationCommand;
import com.commerce.member.domain.Member;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthSignUpService {

	private final MemberRegistrationService memberRegistrationService;
	private final AuthTokenIssueUseCase authTokenIssueService;
	private final PasswordHasher passwordHasher;

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
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
