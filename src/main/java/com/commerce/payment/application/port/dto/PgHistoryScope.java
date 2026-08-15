package com.commerce.payment.application.port.dto;

/**
 * 이력에서 무엇을 받아 올 것인가.
 *
 * <p>환불 판정은 환불 항목만 있으면 되므로 걸러 받아 페이지 수를 줄이고, 승인 판정은 걸러 받지
 * 않는다 — 승인이 성립했는지와 그 뒤 되돌려졌는지를 함께 봐야 한다.
 */
public enum PgHistoryScope {

	/** 원결제와 환불을 모두 받는다 */
	ALL,
	/** 환불 항목만 받는다 */
	REFUND_ONLY
}
