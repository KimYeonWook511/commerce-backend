package com.commerce.auth.service.response;

import com.commerce.member.domain.Member;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;

@Getter
public class AuthLoginResponse {

	private MemberDetailResponse memberDetailResponse;
	private String accessToken;
	private String refreshToken;

	@Builder(access = AccessLevel.PRIVATE)
	private AuthLoginResponse(MemberDetailResponse memberDetailResponse, String accessToken, String refreshToken) {
		this.memberDetailResponse = memberDetailResponse;
		this.accessToken = accessToken;
		this.refreshToken = refreshToken;
	}

	public static AuthLoginResponse from(Member member, String accessToken, String refreshToken) {
		return AuthLoginResponse.builder()
			.memberDetailResponse(MemberDetailResponse.from(member))
			.accessToken(accessToken)
			.refreshToken(refreshToken)
			.build();
	}

}
