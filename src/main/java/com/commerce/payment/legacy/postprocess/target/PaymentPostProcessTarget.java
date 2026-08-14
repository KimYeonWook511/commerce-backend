package com.commerce.payment.legacy.postprocess.target;

public enum PaymentPostProcessTarget {
	APPROVE_RECONCILE("APPROVE UNKNOWN 또는 stale REQUESTED. PG 조회로 승인 결과를 확정할 대상"),
	CANCEL_RECONCILE("CANCEL UNKNOWN·stale REQUESTED·재시도 가능 FAILED. PG 조회로 취소 상태를 확정할 대상"),
	APPROVED_CANCEL_COMPENSATION("실시간 보상이 끊겨 cancel 기록이 없는 잔여. cancel 기록 생성 + 취소 요청 대상"),
	MANUAL_REVIEW("자동 처리 불가. 운영자 확인 대상"),
	NONE("추가 후처리 불필요");

	private final String description;

	PaymentPostProcessTarget(String description) {
		this.description = description;
	}

	public String getDescription() {
		return description;
	}
}
