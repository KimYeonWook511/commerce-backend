package com.commerce.payment.naverpay.service;

public enum NaverPayApproveCode {
	SUCCESS("Success", "성공", false, false),
	FAIL("Fail", "PG, 은행 및 기타 오류 발생 시 오류 상세 원인이 message 필드로 전달됩니다. 결제 실패 사유를 인지할 수 있도록 안내가 필요합니다.", false, false),
	INVALID_MERCHANT("InvalidMerchant", "유효하지 않은 가맹점인 경우", false, false),
	TIME_EXPIRED("TimeExpired", "결제 승인 가능 시간 초과 시 (10분 초과시)", false, false),
	ALREADY_ON_GOING("AlreadyOnGoing", "해당 결제번호로 결제가 이미 진행 중일 때", true, false),
	ALREADY_COMPLETE("AlreadyComplete", "해당 결제번호로 이미 결제가 완료되었을 때", true, false),
	OWNER_AUTH_FAIL("OwnerAuthFail", "본인 카드 인증 오류 시", false, false),
	BANK_MAINTENANCE("BankMaintenance", "충전 계좌 점검 시", false, true),
	NOT_ENOUGH_ACCOUNT_BALANCE("NotEnoughAccountBalance", "충전 계좌 잔고 부족", false, false),
	MAINTENANCE_ONGOING("MaintenanceOngoing", "서비스 점검중", false, true),
	FAULT_CHECK_ONGOING("FaultCheckOngoing", "원천사 시스템 점검으로 해당 결제수단을 이용할 수 없을 때", false, true);

	private final String code;
	private final String description;
	private final boolean idempotent;
	private final boolean retryable;

	NaverPayApproveCode(String code, String description, boolean idempotent, boolean retryable) {
		this.code = code;
		this.description = description;
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

	public String getDescription() {
		return description;
	}
}
