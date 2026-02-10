package com.commerce.payment.service.command;

import com.commerce.payment.domain.PaymentProvider;

import lombok.Builder;
import lombok.Getter;

@Getter
public class PaymentReadyCommand {

	private Long memberId;
	private Long orderId;
	private PaymentProvider provider;

	@Builder
	private PaymentReadyCommand(Long memberId, Long orderId, PaymentProvider provider) {
		this.memberId = memberId;
		this.orderId = orderId;
		this.provider = provider;
	}
}
