package com.commerce.auth.service.command;

import lombok.Builder;
import lombok.Getter;

@Getter
public class AuthLoginCommand {

	private String email;
	private String password;

	@Builder
	private AuthLoginCommand(String email, String password) {
		this.email = email;
		this.password = password;
	}
}
