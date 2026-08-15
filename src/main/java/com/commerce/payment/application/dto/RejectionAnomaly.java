package com.commerce.payment.application.dto;

/**
 * 승인 반려를 커밋하면서 드러난 정합성 이상.
 *
 * <p>값으로 돌려주는 것은 통지를 커밋 뒤로 미루기 위해서다. 트랜잭션 안에서 던지면 되돌릴 근거가
 * 통째로 롤백되는데, 그것이 승인 반려가 막으려는 바로 그 상태다.
 */
public enum RejectionAnomaly {

	NONE(null),

	/** 반려하는 시점에 결제가 이미 종착이었다. 정상이면 그럴 수 없어 어딘가 어긋났다는 신호다 */
	PAYMENT_ALREADY_CLOSED("반려 시점에 결제가 이미 종결돼 있었다"),

	/** 남은 한도가 0이라 되돌릴 환불을 만들지 못했다 */
	NO_REFUNDABLE_AMOUNT("남은 환불 한도가 0이라 되돌릴 환불을 만들지 못했다");

	private final String description;

	RejectionAnomaly(String description) {
		this.description = description;
	}

	public boolean isAnomalous() {
		return this != NONE;
	}

	public String description() {
		return description;
	}
}
