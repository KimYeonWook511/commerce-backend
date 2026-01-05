package com.commerce.auth.controller.request;

import com.commerce.auth.service.request.AuthLoginServiceRequest;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AuthLoginRequest {

	@Email
	@NotBlank
	private String email;

	@NotBlank
	private String password;

	public AuthLoginServiceRequest toServiceRequest() {
		return AuthLoginServiceRequest.builder()
			.email(this.email)
			.password(this.password)
			.build();
	}

}
