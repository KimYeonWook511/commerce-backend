package com.commerce.common.security.context;

import com.commerce.common.security.Role;

/**
 * 인증된 요청의 신원. TokenValidator가 토큰 검증 후 만들어 반환하고,
 * 필터가 {@link AuthenticationContextHolder}에 보관한다. 인터셉터·resolver는 이 값으로만 신원을 읽는다.
 */
public record AuthenticationContext(Long memberId, Role role) {
}
