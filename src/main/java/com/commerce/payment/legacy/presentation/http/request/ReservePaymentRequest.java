package com.commerce.payment.legacy.presentation.http.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
public class ReservePaymentRequest {

	@NotNull(message = "주문 ID는 필수입니다")
	private Long orderId;

	@NotBlank(message = "결제 수단은 필수입니다")
	private String provider;

}
