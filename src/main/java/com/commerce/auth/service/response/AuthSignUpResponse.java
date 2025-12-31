package com.commerce.auth.service.response;

import com.commerce.member.domain.Member;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;

@Getter
public class AuthSignUpResponse {

	private MemberDetailResponse memberDetailResponse;
	private String accessToken;
	private String refreshToken;

	@Builder(access = AccessLevel.PRIVATE)
	private AuthSignUpResponse(MemberDetailResponse memberDetailResponse, String accessToken, String refreshToken) {
		this.memberDetailResponse = memberDetailResponse;
		this.accessToken = accessToken;
		this.refreshToken = refreshToken;
	}

	public static AuthSignUpResponse from(Member member, String accessToken, String refreshToken) {
		return AuthSignUpResponse.builder()
			.memberDetailResponse(MemberDetailResponse.from(member))
			.accessToken(accessToken)
			.refreshToken(refreshToken)
			.build();
	}
}
