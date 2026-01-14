package com.commerce.product.exception;

import org.springframework.http.HttpStatus;

import com.commerce.common.exception.ErrorCode;

public enum ProductErrorCode implements ErrorCode {
	PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "PRODUCT-404", "상품을 찾을 수 없습니다");

	private final HttpStatus status;
	private final String code;
	private final String message;

	ProductErrorCode(HttpStatus status, String code, String message) {
		this.status = status;
		this.code = code;
		this.message = message;
	}

	@Override
	public HttpStatus getStatus() {
		return status;
	}

	@Override
	public String getCode() {
		return code;
	}

	@Override
	public String getMessage() {
		return message;
	}
}
