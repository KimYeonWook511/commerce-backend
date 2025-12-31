package com.commerce.auth.controller.request;

import com.commerce.auth.service.request.AuthSignUpServiceRequest;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AuthSignUpRequest {

	@Email
	@NotBlank
	private String email;

	@NotBlank
	@Size(min = 8, max = 20)
	private String password;

	@NotBlank
	@Size(max = 12)
	private String username;

	public AuthSignUpServiceRequest toServiceRequest() {
		return AuthSignUpServiceRequest.builder()
			.email(this.email)
			.password(this.password)
			.username(this.username)
			.build();
	}

}
