package com.commerce.auth.application.usecase;

import org.springframework.stereotype.Component;

import com.commerce.auth.application.dto.AuthSignUpCommand;
import com.commerce.auth.application.dto.AuthSignUpResult;
import com.commerce.auth.application.dto.AuthTokenIssueResult;
import com.commerce.auth.application.port.PasswordHasher;
import com.commerce.member.application.service.RegisterMemberService;
import com.commerce.member.application.dto.MemberRegistrationCommand;
import com.commerce.member.domain.Member;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthSignUpUseCase {

	private final RegisterMemberService registerMemberService;
	private final AuthTokenIssueUseCase authTokenIssueUseCase;
	private final PasswordHasher passwordHasher;

	public AuthSignUpResult signUp(AuthSignUpCommand command) {
		Member member = registerMemberService.register(MemberRegistrationCommand.builder()
			.email(command.getEmail())
			.passwordHash(passwordHasher.hash(command.getPassword()))
			.username(command.getUsername())
			.build());

		AuthTokenIssueResult tokenIssueResult = authTokenIssueUseCase.issue(member);

		log.info("회원 가입 성공 memberId={}", member.getId());
		return AuthSignUpResult.from(
			member,
			tokenIssueResult.getAccessToken(),
			tokenIssueResult.getRefreshToken()
		);
	}
}
