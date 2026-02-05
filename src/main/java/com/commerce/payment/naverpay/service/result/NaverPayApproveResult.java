package com.commerce.payment.naverpay.service.result;

import lombok.Builder;
import lombok.Getter;

@Getter
public class NaverPayApproveResult {

	private String pgPaymentId;
	private NaverPayApproveStatus status;

	@Builder
	private NaverPayApproveResult(String pgPaymentId, NaverPayApproveStatus status) {
		this.pgPaymentId = pgPaymentId;
		this.status = status;
	}
}
