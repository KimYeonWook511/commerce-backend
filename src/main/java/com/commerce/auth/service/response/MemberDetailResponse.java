package com.commerce.auth.service.response;

import com.commerce.member.domain.Member;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;

@Getter
public class MemberDetailResponse {

	private Long memberId;
	private String email;
	private String username;

	@Builder(access = AccessLevel.PRIVATE)
	private MemberDetailResponse(Long memberId, String email, String username) {
		this.memberId = memberId;
		this.email = email;
		this.username = username;
	}

	public static MemberDetailResponse from(Member member) {
		return MemberDetailResponse.builder()
			.memberId(member.getId())
			.email(member.getEmail())
			.username(member.getUsername())
			.build();
	}

}
