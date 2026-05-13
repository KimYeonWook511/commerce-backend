package com.commerce.payment.naverpay.infrastructure.client.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;

@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class NaverPayResponse<T> {

	private String code;
	private String message;
	private T body;

}
