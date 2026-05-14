package com.commerce.auth.application.port.vo;

public record ParsedTokenClaims(String subject, String role) {

	public static ParsedTokenClaims of(String subject, String role) {
		return new ParsedTokenClaims(subject, role);
	}
}
