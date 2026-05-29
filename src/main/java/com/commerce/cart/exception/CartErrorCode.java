package com.commerce.cart.exception;

import org.springframework.http.HttpStatus;

import com.commerce.common.exception.ErrorCode;

public enum CartErrorCode implements ErrorCode {
	INVALID_CART_ITEM_QUANTITY(HttpStatus.BAD_REQUEST, "CART-400-1", "장바구니 수량이 올바르지 않습니다"),
	CART_ITEM_QUANTITY_EXCEEDED(HttpStatus.BAD_REQUEST, "CART-400-2", "장바구니 수량 한도를 초과했습니다"),
	CART_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "CART-404-1", "장바구니 항목을 찾을 수 없습니다"),
	CART_ITEM_PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "CART-404-2", "장바구니에 담을 상품을 찾을 수 없습니다"),
	CART_ITEM_PRODUCT_UNAVAILABLE(HttpStatus.CONFLICT, "CART-409", "장바구니에 담을 수 없는 상품입니다");

	private final HttpStatus status;
	private final String code;
	private final String message;

	CartErrorCode(HttpStatus status, String code, String message) {
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
