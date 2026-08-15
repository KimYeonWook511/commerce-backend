package com.commerce.payment.infrastructure.pg.naverpay.client.response.body;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;

/** 취소 응답 본문 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class CancelBody {

	private String paymentId;
	/** 결제사가 발급한 취소 거래 번호 */
	private String payHistId;
	private String cancelYmdt;
	private int totalRestAmount;
}
