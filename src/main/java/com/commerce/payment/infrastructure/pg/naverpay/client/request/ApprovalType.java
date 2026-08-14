package com.commerce.payment.infrastructure.pg.naverpay.client.request;

/** 이력 조회에서 어떤 항목을 달라고 할 것인가. 결제사가 정한 값이다 */
public enum ApprovalType {
	ALL,
	APPROVAL,
	CANCEL
}
