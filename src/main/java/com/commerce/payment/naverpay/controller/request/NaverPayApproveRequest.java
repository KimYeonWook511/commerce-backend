package com.commerce.payment.naverpay.controller.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class NaverPayApproveRequest {

	@NotBlank
	private String merchantPayKey;

	@NotBlank
	private String paymentId;
}
