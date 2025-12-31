package com.commerce.auth.context;

public final class AuthenticationContext {

	private static final ThreadLocal<Long> memberIdHolder = new ThreadLocal<>();
	private static final ThreadLocal<String> roleHolder = new ThreadLocal<>();

	public static void set(Long memberId, String role) {
		memberIdHolder.set(memberId);
		roleHolder.set(role);
	}

	public static Long getMemberId() {
		return memberIdHolder.get();
	}

	public static String getRole() {
		return roleHolder.get();
	}

	public static void clear() {
		memberIdHolder.remove();
		roleHolder.remove();
	}
}

