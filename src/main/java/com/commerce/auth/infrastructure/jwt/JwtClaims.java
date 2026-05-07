package com.commerce.auth.infrastructure.jwt;

import com.commerce.member.domain.MemberRole;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;

@Getter
public class JwtClaims {

	private Long memberId; // subject
	private MemberRole memberRole;
	private JwtType tokenType;

	@Builder(access = AccessLevel.PRIVATE)
	private JwtClaims(Long memberId, MemberRole memberRole, JwtType tokenType) {
		this.memberId = memberId;
		this.memberRole = memberRole;
		this.tokenType = tokenType;
	}

	public static JwtClaims of(Long memberId, MemberRole memberRole, JwtType tokenType) {
		return JwtClaims.builder()
			.memberId(memberId)
			.memberRole(memberRole)
			.tokenType(tokenType)
			.build();
	}
}
