package com.commerce.payment.naverpay.service;

public enum NaverPayResultCode {
	SUCCESS("Success"),
	USER_CANCEL("UserCancel"),
	TIME_EXPIRED("TimeExpired"),
	UNDER_AGE_AMOUNT_LIMIT("UnderAgeAmountLimit"),
	FAIL("Fail");

	private final String code;

	NaverPayResultCode(String code) {
		this.code = code;
	}

	public static NaverPayResultCode from(String code) {
		for (NaverPayResultCode value : values()) {
			if (value.code.equalsIgnoreCase(code)) {
				return value;
			}
		}
		return FAIL;
	}

	public boolean isSuccess() {
		return this == SUCCESS;
	}
}
