package com.commerce.payment.presentation.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class PaymentReadyRequest {

	@NotNull(message = "주문 ID는 필수입니다")
	private Long orderId;

	@NotBlank(message = "결제 수단은 필수입니다")
	private String provider;

}
