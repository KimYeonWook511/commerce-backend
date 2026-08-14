package com.commerce.payment.legacy.application.dto;

import com.commerce.payment.legacy.domain.PaymentProvider;

import lombok.Builder;
import lombok.Getter;

@Getter
public class ReservePaymentCommand {

	private Long memberId;
	private Long orderId;
	private PaymentProvider provider;

	@Builder
	private ReservePaymentCommand(Long memberId, Long orderId, PaymentProvider provider) {
		this.memberId = memberId;
		this.orderId = orderId;
		this.provider = provider;
	}
}
