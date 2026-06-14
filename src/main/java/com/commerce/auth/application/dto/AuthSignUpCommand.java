package com.commerce.auth.application.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
public class AuthSignUpCommand {

	private String email;
	private String password;
	private String username;

	@Builder
	private AuthSignUpCommand(String email, String password, String username) {
		this.email = email;
		this.password = password;
		this.username = username;
	}

}
