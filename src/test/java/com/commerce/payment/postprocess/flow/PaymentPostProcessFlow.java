package com.commerce.payment.postprocess.flow;

public enum PaymentPostProcessFlow {
	APPROVED_PAYMENT_PROCESS("PG 승인 완료가 확인된 결제를 승인 완료 처리로 진행한다"),
	CANCEL_ATTEMPT_CREATION_AND_CANCEL_REQUEST_PROCESS("cancel attempt를 만들고 취소 요청 처리로 진행한다"),
	ALREADY_CANCELED_PAYMENT_PROCESS("이미 취소된 결제를 ALREADY_CANCELED로 종결 처리한다"),
	CANCEL_RETRY_PROCESS("취소가 미완료된 결제를 다시 취소 요청한다"),
	MANUAL_REVIEW_PROCESS("자동 처리하지 않고 수동 확인 대상으로 남긴다"),
	KEEP_WAITING("아직 상태가 확정되지 않아 다음 후처리 주기까지 대기한다"),
	NONE("추가 후처리가 필요하지 않다");

	private final String description;

	PaymentPostProcessFlow(String description) {
		this.description = description;
	}

	public String getDescription() {
		return description;
	}
}
