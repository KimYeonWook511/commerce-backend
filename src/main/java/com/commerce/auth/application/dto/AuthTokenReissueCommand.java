package com.commerce.auth.application.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
public class AuthTokenReissueCommand {

	private String refreshToken;

	@Builder
	private AuthTokenReissueCommand(String refreshToken) {
		this.refreshToken = refreshToken;
	}
}
