package com.commerce.auth.application.port.vo;

import com.commerce.member.domain.MemberRole;

public record TokenClaims(Long memberId, MemberRole memberRole) {

	public static TokenClaims of(Long memberId, MemberRole memberRole) {
		return new TokenClaims(memberId, memberRole);
	}
}
