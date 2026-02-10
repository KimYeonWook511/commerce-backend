package com.commerce.auth.service.result;

import com.commerce.member.domain.Member;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;

@Getter
public class MemberDetailResult {

	private Long memberId;
	private String email;
	private String username;

	@Builder(access = AccessLevel.PRIVATE)
	private MemberDetailResult(Long memberId, String email, String username) {
		this.memberId = memberId;
		this.email = email;
		this.username = username;
	}

	public static MemberDetailResult from(Member member) {
		return MemberDetailResult.builder()
			.memberId(member.getId())
			.email(member.getEmail())
			.username(member.getUsername())
			.build();
	}

}
