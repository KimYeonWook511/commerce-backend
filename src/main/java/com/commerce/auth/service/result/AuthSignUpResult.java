package com.commerce.auth.service.result;

import com.commerce.member.domain.Member;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;

@Getter
public class AuthSignUpResult {

	private MemberDetailResult memberDetailResult;
	private String accessToken;
	private String refreshToken;

	@Builder(access = AccessLevel.PRIVATE)
	private AuthSignUpResult(MemberDetailResult memberDetailResult, String accessToken, String refreshToken) {
		this.memberDetailResult = memberDetailResult;
		this.accessToken = accessToken;
		this.refreshToken = refreshToken;
	}

	public static AuthSignUpResult from(Member member, String accessToken, String refreshToken) {
		return AuthSignUpResult.builder()
			.memberDetailResult(MemberDetailResult.from(member))
			.accessToken(accessToken)
			.refreshToken(refreshToken)
			.build();
	}
}
