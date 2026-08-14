package com.commerce.payment.legacy.naverpay.application.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
public class NaverPayApproveResponse {

	private String pgPaymentId;
	private NaverPayApproveStatus status;

	@Builder
	private NaverPayApproveResponse(String pgPaymentId, NaverPayApproveStatus status) {
		this.pgPaymentId = pgPaymentId;
		this.status = status;
	}
}
