package com.commerce.payment.domain;

/**
 * 왜 자동으로 더 못 하게 됐나. 이 값이 채워졌다는 것이 곧 사람이 처리해야 하는 상태이며 둘은 함께
 * 움직인다.
 *
 * <p>결제 종결 코드와 목록을 나눠 갖는다. 한 목록을 공유하면 값의 절반이 서로에게 의미가 없다.
 * 값마다 돈이 나갔는지가 달라, 관리자가 성공으로 정리할지 값을 고쳐 다시 보낼지를 이것으로 가른다.
 */
public enum RefundReviewCode {

	/** 취소 기한이 지나 결제사로는 못 돌려준다. 돈은 안 나갔고 다른 수단으로 돌려줘야 한다 */
	CANCEL_DEADLINE_EXPIRED,
	/** 할인 정책 등으로 이 결제는 취소할 수 없다. 돈은 안 나갔다 */
	CANCEL_NOT_ALLOWED,
	/** 결제사 잔액이 우리 기록보다 적다. 돈이 나갔는지 모른다 */
	REFUNDABLE_AMOUNT_EXCEEDED,
	/** 우리가 보낸 값이 결제사 기준에 맞지 않는다. 돈은 안 나갔다 */
	REQUEST_REJECTED
}
