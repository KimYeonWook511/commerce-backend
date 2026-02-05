package com.commerce.payment.naverpay.service;

public enum NaverPayResultCode {
	SUCCESS("Success", "성공"),
	USER_CANCEL("UserCancel", "구매자 취소. 팝업으로 결제 창을 호출한 경우에 해당 resultCode를 받은 경우 서비스 시나리오에 따라 적절하게 창을 닫아야 합니다"),
	TIME_EXPIRED("TimeExpired", "결제 시간 초과(reserveId 생성 후 30분을 초과한 경우)"),
	UNDER_AGE_AMOUNT_LIMIT("UnderAgeAmountLimit", "미성년자 월 결제 혹은 일 결제 한도 초과"),
	FAIL("Fail", "성공(Success)이 아닌 resultCode에 대해서는 적절한 사용자 안내 처리 또는 resultMessage 그대로 전달");

	private final String code;
	private final String description;

	NaverPayResultCode(String code, String description) {
		this.code = code;
		this.description = description;
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

	public String getDescription() {
		return description;
	}
}
