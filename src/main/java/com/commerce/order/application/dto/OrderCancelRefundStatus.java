package com.commerce.order.application.dto;

public enum OrderCancelRefundStatus {
	/** 환불 없음 (INIT 주문 취소) */
	NONE,
	/** PG 환불 완료 */
	COMPLETED,
	/** PG 환불 처리 중 (CANCEL 대사가 마무리) */
	IN_PROGRESS
}
