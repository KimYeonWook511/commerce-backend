package com.commerce.payment.legacy.naverpay.infrastructure.pg.code;

public enum NaverPayHistoryCode {
	SUCCESS("Success", "성공"),
	INVALID_MERCHANT("InvalidMerchant", "유효하지 않은 가맹점"),
	REQUIRE_CONDITION("RequireCondition", "입력값이 조건을 만족하지 않습니다."),
	MAINTENANCE_ONGOING("MaintenanceOngoing", "서비스 점검중"),
	FAIL("Fail", "기타 실패");

	private final String code;
	private final String description;

	NaverPayHistoryCode(String code, String description) {
		this.code = code;
		this.description = description;
	}

	public String getCode() {
		return code;
	}

	public String getDescription() {
		return description;
	}

	public static NaverPayHistoryCode from(String code) {
		for (NaverPayHistoryCode value : values()) {
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
