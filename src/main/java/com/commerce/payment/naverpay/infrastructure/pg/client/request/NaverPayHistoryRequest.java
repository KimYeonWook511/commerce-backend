package com.commerce.payment.naverpay.infrastructure.pg.client.request;

import lombok.Builder;
import lombok.Getter;

@Getter
public class NaverPayHistoryRequest {

	private final NaverPayApprovalType approvalType;
	private final int pageNumber;
	private final int rowsPerPage;

	@Builder
	private NaverPayHistoryRequest(NaverPayApprovalType approvalType, int pageNumber,
		int rowsPerPage) {
		this.approvalType = approvalType == null ? NaverPayApprovalType.ALL : approvalType;
		this.pageNumber = pageNumber == 0 ? 1 : pageNumber;
		this.rowsPerPage = rowsPerPage == 0 ? 50 : rowsPerPage;
	}
}
