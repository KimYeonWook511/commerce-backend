package com.commerce.auth.service.response;

import lombok.Builder;
import lombok.Getter;

@Getter
public class AuthTokenReissueResponse {

	private String accessToken;
	private String refreshToken;

	@Builder
	private AuthTokenReissueResponse(String accessToken, String refreshToken) {
		this.accessToken = accessToken;
		this.refreshToken = refreshToken;
	}

	public static AuthTokenReissueResponse of(String accessToken, String refreshToken) {
		return AuthTokenReissueResponse.builder()
			.accessToken(accessToken)
			.refreshToken(refreshToken)
			.build();
	}
}
