package com.commerce.payment.naverpay.service.result;

import com.commerce.payment.domain.PaymentStatus;

import lombok.Builder;
import lombok.Getter;

@Getter
public class NaverPayApproveResult {

	private Long orderId;
	private String pgPaymentId;
	private PaymentStatus status;

	@Builder
	private NaverPayApproveResult(Long orderId, String pgPaymentId, PaymentStatus status) {
		this.orderId = orderId;
		this.pgPaymentId = pgPaymentId;
		this.status = status;
	}
}
