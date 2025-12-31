package com.commerce.auth.jwt;

import com.commerce.member.domain.Member;
import com.commerce.member.domain.MemberRole;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;

@Getter
public class JwtTokenClaims {

	private Long memberId; // subject
	private MemberRole memberRole;
	private JwtTokenType tokenType;

	@Builder(access = AccessLevel.PRIVATE)
	private JwtTokenClaims(Long memberId, MemberRole memberRole, JwtTokenType tokenType) {
		this.memberId = memberId;
		this.memberRole = memberRole;
		this.tokenType = tokenType;
	}

	public static JwtTokenClaims from(Member member, JwtTokenType tokenType) {
		return JwtTokenClaims.builder()
			.memberId(member.getId())
			.memberRole(member.getRole())
			.tokenType(tokenType)
			.build();
	}
}
