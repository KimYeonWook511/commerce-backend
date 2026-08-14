package com.commerce.payment.legacy.naverpay.infrastructure.pg.client.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;

@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class NaverPayResponse<T> {

	private String code;
	private String message;
	private T body;

}
