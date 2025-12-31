package com.commerce.auth.service.request;

import lombok.Builder;
import lombok.Getter;

@Getter
public class AuthSignUpServiceRequest {

	private String email;
	private String password;
	private String username;

	@Builder
	private AuthSignUpServiceRequest(String email, String password, String username) {
		this.email = email;
		this.password = password;
		this.username = username;
	}

}
