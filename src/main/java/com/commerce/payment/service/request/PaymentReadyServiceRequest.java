package com.commerce.payment.service.request;

import com.commerce.payment.domain.PaymentProvider;

import lombok.Builder;
import lombok.Getter;

@Getter
public class PaymentReadyServiceRequest {

	private Long memberId;
	private Long orderId;
	private PaymentProvider provider;

	@Builder
	private PaymentReadyServiceRequest(Long memberId, Long orderId, PaymentProvider provider) {
		this.memberId = memberId;
		this.orderId = orderId;
		this.provider = provider;
	}
}
