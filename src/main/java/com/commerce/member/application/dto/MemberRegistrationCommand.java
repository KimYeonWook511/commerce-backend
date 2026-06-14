package com.commerce.member.application.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
public class MemberRegistrationCommand {

	private final String email;
	private final String passwordHash;
	private final String username;

	@Builder
	private MemberRegistrationCommand(String email, String passwordHash, String username) {
		this.email = email;
		this.passwordHash = passwordHash;
		this.username = username;
	}
}
