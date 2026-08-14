package com.commerce.payment.presentation.http.naverpay.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 승인 요청에 클라이언트가 싣는 값 둘. {@code merchantPayKey}는 우리가 발급한 결제 키이고,
 * {@code paymentId}는 결제사가 발급한 결제 번호다 — 앞엣것으로 신원을 확인하고 뒤엣것을 결제사에
 * 그대로 넘긴다.
 */
@Getter
@NoArgsConstructor
public class NaverPayApproveRequest {

	@NotBlank
	private String merchantPayKey;

	@NotBlank
	private String paymentId;
}
