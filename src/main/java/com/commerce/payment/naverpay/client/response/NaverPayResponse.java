package com.commerce.payment.naverpay.client.response;

import com.commerce.common.ApiResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;

@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class NaverPayResponse<T> {

	private String code;
	private String message;
	private T body;

}
