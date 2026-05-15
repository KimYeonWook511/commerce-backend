package com.commerce.auth.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.auth.application.command.AuthSignUpCommand;
import com.commerce.auth.application.result.AuthSignUpResult;
import com.commerce.auth.application.result.AuthTokenIssueResult;
import com.commerce.auth.application.port.PasswordHasher;
import com.commerce.member.application.MemberRegistrationService;
import com.commerce.member.application.command.MemberRegistrationCommand;
import com.commerce.member.domain.Member;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthSignUpService {

	private final MemberRegistrationService memberRegistrationService;
	private final AuthTokenIssueService authTokenIssueService;
	private final PasswordHasher passwordHasher;

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public AuthSignUpResult signUp(AuthSignUpCommand command) {
		Member member = memberRegistrationService.register(MemberRegistrationCommand.builder()
			.email(command.getEmail())
			.passwordHash(passwordHasher.hash(command.getPassword()))
			.username(command.getUsername())
			.build());

		AuthTokenIssueResult tokenIssueResult = authTokenIssueService.issue(member);

		return AuthSignUpResult.from(
			member,
			tokenIssueResult.getAccessToken(),
			tokenIssueResult.getRefreshToken()
		);
	}
}
