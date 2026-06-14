package com.commerce.payment.naverpay.infrastructure.pg.code;

public enum NaverPayCancelCode {
	SUCCESS("Success", "성공"),
	INVALID_MERCHANT("InvalidMerchant", "유효하지 않은 가맹점"),
	INVALID_PG_PAYMENT_ID("InvalidPaymentId", "유효하지 않은 네이버페이 결제번호"),
	ALREADY_CANCELED("AlreadyCanceled", "이미 전체 취소된 결제"),
	OVER_REMAIN_AMOUNT("OverRemainAmount", "취소 요청 금액이 잔여 결제 금액을 초과"),
	PRE_CANCEL_NOT_COMPLETE(
		"PreCancelNotComplete",
		"이전에 요청한 취소가 완료되지 않은 상태. 이전에 요청했던 취소 정보로 재시도 요청해야 이전 취소가 완료되고 다음 취소를 수행할 수 있습니다."
	),
	CANCEL_DEADLINE_EXPIRED("CancelDeadlineExpired", "취소 기한이 만료되어 취소가 불가합니다. 가맹점의 자체 환불이 필요합니다."),
	TAX_SCOPE_AMT_GREATER_THAN_REMAIN_ERROR("TaxScopeAmtGreaterThanRemainError", "취소 가능한 과면세 금액보다 큰 금액 요청"),
	TAX_SCOPE_AMOUNT_ERROR(
		"TaxScopeAmountError",
		"과면세 및 컵 보증금 금액의 합이 취소 요청 금액과 다른 상태. 과세 대상 금액 + 면세 대상 금액 + 컵 보증금 대상 금액 (옵션) = 취소 요청 금액"
	),
	REST_AMOUNT_DIFF("RestAmountDiff", "가맹점의 예상 잔여금액(expectedRestAmount)과 네이버페이의 잔여금액이 일치하지 않음"),
	CANCEL_NOT_COMPLETE("CancelNotComplete", "취소 처리가 완료되지 않았지만, 빠른 시일 내에 자동 취소 재처리 예정."),
	ALREADY_ON_GOING("AlreadyOnGoing", "해당 결제번호로 요청이 이미 진행 중인 경우"),
	MAINTENANCE_ONGOING("MaintenanceOngoing", "서비스 점검중 (점검 종료 후 반드시 재시도 필요)"),
	FAULT_CHECK_ONGOING("FaultCheckOngoing", "원천사 시스템 점검으로 해당 결제수단을 이용할 수 없을 때"),
	INVALID_DISCOUNT_CANCEL_CONDITION("InvalidDiscountCancelCondition", "즉시 할인 정책에 따라 취소가 불가합니다."),
	FAIL("Fail", "기타 실패");

	private final String code;
	private final String description;

	NaverPayCancelCode(String code, String description) {
		this.code = code;
		this.description = description;
	}

	public String getCode() {
		return code;
	}

	public String getDescription() {
		return description;
	}

	public static NaverPayCancelCode from(String code) {
		for (NaverPayCancelCode value : values()) {
			if (value.code.equalsIgnoreCase(code)) {
				return value;
			}
		}
		return FAIL;
	}

	public boolean isSuccess() {
		return this == SUCCESS;
	}

	public boolean isAlreadyOnGoing() {
		return this == ALREADY_ON_GOING;
	}

	public boolean isAlreadyCanceled() {
		return this == ALREADY_CANCELED;
	}
}
