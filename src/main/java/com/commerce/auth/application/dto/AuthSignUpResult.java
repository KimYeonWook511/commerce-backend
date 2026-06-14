package com.commerce.auth.application.dto;

import com.commerce.member.domain.Member;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;

@Getter
public class AuthSignUpResult {

	private AuthMemberResult member;
	private String accessToken;
	private String refreshToken;

	@Builder(access = AccessLevel.PRIVATE)
	private AuthSignUpResult(AuthMemberResult member, String accessToken, String refreshToken) {
		this.member = member;
		this.accessToken = accessToken;
		this.refreshToken = refreshToken;
	}

	public static AuthSignUpResult from(Member member, String accessToken, String refreshToken) {
		return AuthSignUpResult.builder()
			.member(AuthMemberResult.from(member))
			.accessToken(accessToken)
			.refreshToken(refreshToken)
			.build();
	}
}
