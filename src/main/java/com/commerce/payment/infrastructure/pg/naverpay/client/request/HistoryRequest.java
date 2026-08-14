package com.commerce.payment.infrastructure.pg.naverpay.client.request;

/**
 * 이력 조회 요청 본문.
 *
 * @param approvalType 어떤 항목을 달라고 할 것인가
 * @param pageNumber   1부터 센다
 * @param rowsPerPage  한 페이지에 담을 항목 수. 결제사가 1~100을 받는다
 */
public record HistoryRequest(
	ApprovalType approvalType,
	int pageNumber,
	int rowsPerPage
) {
}
