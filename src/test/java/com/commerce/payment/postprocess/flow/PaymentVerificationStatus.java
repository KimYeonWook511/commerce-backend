package com.commerce.payment.postprocess.flow;

public enum PaymentVerificationStatus {
	PG_APPROVED("PG history 기준으로 승인 완료가 확인된 상태"),
	PG_CANCELED("PG history 기준으로 이미 취소된 것이 확인된 상태"),
	PENDING("PG 처리 결과가 아직 확정되지 않은 상태"),
	HISTORY_NOT_FOUND("PG history에서 결제 이력을 찾지 못한 상태");

	private final String description;

	PaymentVerificationStatus(String description) {
		this.description = description;
	}

	public String getDescription() {
		return description;
	}
}
