package com.commerce.auth.application.dto;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;

@Getter
public class TokenAuthenticationResult {

	private Long memberId;
	private String role;

	@Builder(access = AccessLevel.PRIVATE)
	private TokenAuthenticationResult(Long memberId, String role) {
		this.memberId = memberId;
		this.role = role;
	}

	public static TokenAuthenticationResult of(Long memberId, String role) {
		return TokenAuthenticationResult.builder()
			.memberId(memberId)
			.role(role)
			.build();
	}
}
