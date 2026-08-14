package com.commerce.payment.application.port.dto;

/**
 * 이력 항목이 무엇을 기록한 것인가.
 *
 * <p>결제사 문서는 돈을 되돌린 항목을 "취소"라 부르지만 우리 도메인에서 그것은 환불이다. 어댑터가
 * 그 어휘를 여기서 옮긴다 — 우리말의 "취소"는 주문을 무르는 것이라 그대로 들이면 두 말이 겹친다.
 */
public enum PgHistoryEntryType {

	/** 원결제 항목 */
	APPROVAL,
	/** 돈을 되돌린 항목. 전체취소와 부분취소가 모두 여기 든다 */
	REFUND
}
