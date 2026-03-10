package com.commerce.payment.postprocess.target;

public enum PaymentPostProcessTarget {
	APPROVE_REQUESTED_TARGET("기준 시간이 지난 REQUESTED approve attempt의 상태를 확인한다"),
	FAILED_APPROVE_RESULT_TARGET("기준 시간이 지난 FAILED approve attempt의 승인 결과를 다시 확인한다"),
	MERCHANT_PAY_KEY_MISMATCH_TARGET("merchantPayKey mismatch approve attempt의 PG 상태와 관련 주문 상태를 함께 확인한다"),
	APPROVED_PAYMENT_CANCEL_ACTION("승인은 되었지만 취소 보상이 필요한 결제의 cancel attempt를 만들고 취소를 요청한다"),
	CANCEL_REQUESTED_TARGET("기준 시간이 지난 cancel attempt의 취소 상태를 확인한다"),
	CANCEL_RETRY_TARGET("취소 재시도가 필요한 결제를 다시 취소 요청한다"),
	MANUAL_REVIEW_TARGET("자동 처리하지 않고 수동 확인 대상으로 남긴다"),
	NONE("추가 후처리가 필요하지 않다");

	private final String description;

	PaymentPostProcessTarget(String description) {
		this.description = description;
	}

	public String getDescription() {
		return description;
	}
}
