package com.commerce.payment.domain;

/**
 * 결제가 왜 종결됐나. 상태가 종결의 종류를 말하고 이 값이 왜를 말한다.
 *
 * <p>값 집합이 종결 상태를 결정한다 — 값이 채워졌다는 것은 세 종착 중 하나라는 뜻이고, 어느 것인지는
 * 그 값이 어느 집합에 드는지가 가른다. 그래서 종결 코드만 봐도 어느 종착인지 안다.
 *
 * <p>이름이 {@code fail}이 아니라 {@code close}인 것은 만료와 반려가 실패가 아닌데도 이 값이 붙기
 * 때문이다. 이름이 실패를 가리키면 "실패 건 조회"를 이 값이 있는지로 짜는 사람이 나오고, 자리를 내준
 * 건까지 실패로 집계된다.
 */
public enum PaymentCloseCode {

	/** 결제사가 승인을 거절했다. 세부는 종결 상세에 담는다 */
	PG_DECLINED(PaymentStatus.FAILED),
	/** 결제사 점검·장애로 승인이 안 됐다 */
	PG_UNAVAILABLE(PaymentStatus.FAILED),
	/** 결제 키가 그 결제의 것이 아니다. 공격 또는 버그 */
	PAYMENT_KEY_MISMATCH(PaymentStatus.FAILED),
	/** 승인을 다시 요청했고 결제사가 승인 가능 시간이 지났다고 답해 확정했다 */
	UNCONFIRMED_CLOSED(PaymentStatus.FAILED),

	/** 승인 금액이 우리 주문 금액과 다르다. 위변조 신호 */
	AMOUNT_MISMATCH(PaymentStatus.REJECTED),
	/** 주문이 결제 가능한 상태가 아니다 */
	ORDER_NOT_PAYABLE(PaymentStatus.REJECTED),

	/** 새 결제 요청이 자리를 뺏었다 */
	SUPERSEDED(PaymentStatus.EXPIRED),
	/** 세션이 지나 만료 배치가 정리했다 */
	SESSION_TIMEOUT(PaymentStatus.EXPIRED);

	private final PaymentStatus closedStatus;

	PaymentCloseCode(PaymentStatus closedStatus) {
		this.closedStatus = closedStatus;
	}

	public PaymentStatus closedStatus() {
		return closedStatus;
	}
}
