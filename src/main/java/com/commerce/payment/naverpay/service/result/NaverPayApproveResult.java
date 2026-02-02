package com.commerce.payment.naverpay.service.result;

import lombok.Builder;
import lombok.Getter;

@Getter
public class NaverPayApproveResult {

	private Long orderId;
	private String pgPaymentId;
	private NaverPayApproveStatus status;

	@Builder
	private NaverPayApproveResult(Long orderId, String pgPaymentId, NaverPayApproveStatus status) {
		this.orderId = orderId;
		this.pgPaymentId = pgPaymentId;
		this.status = status;
	}
}
