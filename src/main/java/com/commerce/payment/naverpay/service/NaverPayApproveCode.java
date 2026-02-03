package com.commerce.payment.naverpay.service;

public enum NaverPayApproveCode {
	SUCCESS("Success", false, false),
	FAIL("Fail", false, false),
	INVALID_MERCHANT("InvalidMerchant", false, false),
	TIME_EXPIRED("TimeExpired", false, false),
	ALREADY_ON_GOING("AlreadyOnGoing", true, false),
	ALREADY_COMPLETE("AlreadyComplete", true, false),
	OWNER_AUTH_FAIL("OwnerAuthFail", false, false),
	BANK_MAINTENANCE("BankMaintenance", false, true),
	NOT_ENOUGH_ACCOUNT_BALANCE("NotEnoughAccountBalance", false, false),
	MAINTENANCE_ONGOING("MaintenanceOngoing", false, true),
	FAULT_CHECK_ONGOING("FaultCheckOngoing", false, true);

	private final String code;
	private final boolean idempotent;
	private final boolean retryable;

	NaverPayApproveCode(String code, boolean idempotent, boolean retryable) {
		this.code = code;
		this.idempotent = idempotent;
		this.retryable = retryable;
	}

	public static NaverPayApproveCode from(String code) {
		for (NaverPayApproveCode value : values()) {
			if (value.code.equalsIgnoreCase(code)) {
				return value;
			}
		}
		return FAIL;
	}

	public boolean isSuccess() {
		return this == SUCCESS;
	}

	public boolean isIdempotent() {
		return idempotent;
	}

	public boolean isRetryable() {
		return retryable;
	}
}
