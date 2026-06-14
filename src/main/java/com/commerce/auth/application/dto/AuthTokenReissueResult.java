package com.commerce.auth.application.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
public class AuthTokenReissueResult {

	private String accessToken;
	private String refreshToken;

	@Builder
	private AuthTokenReissueResult(String accessToken, String refreshToken) {
		this.accessToken = accessToken;
		this.refreshToken = refreshToken;
	}

	public static AuthTokenReissueResult of(String accessToken, String refreshToken) {
		return AuthTokenReissueResult.builder()
			.accessToken(accessToken)
			.refreshToken(refreshToken)
			.build();
	}
}
