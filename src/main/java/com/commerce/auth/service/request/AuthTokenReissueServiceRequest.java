package com.commerce.auth.service.request;

import lombok.Builder;
import lombok.Getter;

@Getter
public class AuthTokenReissueServiceRequest {

	private String refreshToken;

	@Builder
	private AuthTokenReissueServiceRequest(String refreshToken) {
		this.refreshToken = refreshToken;
	}
}
