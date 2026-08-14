package com.commerce.payment.infrastructure.pg.naverpay.client.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;

/** 결제사 응답의 공통 껍데기. 성공·실패가 상태 코드가 아니라 이 안의 결과 코드로 갈린다 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class GatewayResponse<T> {

	private String code;
	private String message;
	private T body;
}
