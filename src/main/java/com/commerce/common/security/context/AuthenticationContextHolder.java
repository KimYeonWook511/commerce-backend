package com.commerce.common.security.context;

/**
 * 요청 thread 동안 {@link AuthenticationContext}를 보관하는 ThreadLocal 홀더.
 * 필터가 요청 처리 시작 시 set, 종료 시 clear로 생명주기를 관리한다.
 */
public final class AuthenticationContextHolder {

	private static final ThreadLocal<AuthenticationContext> CONTEXT = new ThreadLocal<>();

	private AuthenticationContextHolder() {
	}

	public static void set(AuthenticationContext context) {
		CONTEXT.set(context);
	}

	public static AuthenticationContext get() {
		return CONTEXT.get();
	}

	public static void clear() {
		CONTEXT.remove();
	}
}
