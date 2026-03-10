package com.commerce.payment.postprocess.flow;

public enum RelatedOrderStatus {
	PAID("실제 merchantPayKey 주문이 이미 결제 완료 상태인 경우"),
	INIT("실제 merchantPayKey 주문이 아직 결제 완료 전 상태인 경우"),
	NOT_FOUND("실제 merchantPayKey 주문을 찾지 못한 경우");

	private final String description;

	RelatedOrderStatus(String description) {
		this.description = description;
	}

	public String getDescription() {
		return description;
	}
}
