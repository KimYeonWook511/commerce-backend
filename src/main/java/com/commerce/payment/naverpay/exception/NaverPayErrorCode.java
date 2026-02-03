package com.commerce.payment.naverpay.exception;

public enum NaverPayErrorCode {
	NETWORK(true),
	SERVER_ERROR(true),
	CLIENT_ERROR(false),
	AUTHENTICATION(false),
	INVALID_RESPONSE(false);

	private final boolean retryable;

	NaverPayErrorCode(boolean retryable) {
		this.retryable = retryable;
	}

	public boolean isRetryable() {
		return retryable;
	}
}
