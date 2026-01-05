package com.commerce.auth.service.request;

import lombok.Builder;
import lombok.Getter;

@Getter
public class AuthLoginServiceRequest {

	private String email;
	private String password;

	@Builder
	private AuthLoginServiceRequest(String email, String password) {
		this.email = email;
		this.password = password;
	}
}
