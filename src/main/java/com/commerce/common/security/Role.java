package com.commerce.common.security;

/**
 * 인가(authorization) 어휘. 웹 진입점에서 권한을 판정하는 authz leaf가 소유한다.
 * 도메인의 회원 분류(member의 MemberRole)와 상수 이름이 같아 JWT의 role 문자열로 서로 오간다
 * (발급 시 name() → 문자열, 검증 시 valueOf(문자열) → Role). 둘은 서로를 직접 참조하지 않는다.
 */
public enum Role {
	ROLE_ADMIN,
	ROLE_USER,
}
