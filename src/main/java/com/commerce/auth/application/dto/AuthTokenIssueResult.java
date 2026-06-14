package com.commerce.auth.application.result;

import lombok.Builder;
import lombok.Getter;

@Getter
public class AuthTokenIssueResult {

	private String accessToken;
	private String refreshToken;

	@Builder
	private AuthTokenIssueResult(String accessToken, String refreshToken) {
		this.accessToken = accessToken;
		this.refreshToken = refreshToken;
	}

	public static AuthTokenIssueResult of(String accessToken, String refreshToken) {
		return AuthTokenIssueResult.builder()
			.accessToken(accessToken)
			.refreshToken(refreshToken)
			.build();
	}
}
