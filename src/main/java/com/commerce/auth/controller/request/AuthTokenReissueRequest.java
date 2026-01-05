package com.commerce.auth.controller.request;

import com.commerce.auth.service.request.AuthTokenReissueServiceRequest;

import jakarta.validation.constraints.NotBlank;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AuthTokenReissueRequest {

	@NotBlank
	private String refreshToken;

	public AuthTokenReissueServiceRequest toServiceRequest() {
		return AuthTokenReissueServiceRequest.builder()
			.refreshToken(this.refreshToken)
			.build();
	}
}
