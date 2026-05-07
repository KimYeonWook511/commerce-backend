package com.commerce.auth.application.result;

import com.commerce.member.domain.Member;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;

@Getter
public class AuthMemberResult {

	private Long memberId;
	private String email;
	private String username;

	@Builder(access = AccessLevel.PRIVATE)
	private AuthMemberResult(Long memberId, String email, String username) {
		this.memberId = memberId;
		this.email = email;
		this.username = username;
	}

	public static AuthMemberResult from(Member member) {
		return AuthMemberResult.builder()
			.memberId(member.getId())
			.email(member.getEmail())
			.username(member.getUsername())
			.build();
	}

}
