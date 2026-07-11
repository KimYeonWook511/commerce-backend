package com.commerce.cart.domain.exception;

import com.commerce.common.exception.ErrorCategory;
import com.commerce.common.exception.ErrorCode;

public enum CartErrorCode implements ErrorCode {
	INVALID_CART_ITEM_QUANTITY(ErrorCategory.INVALID, "CART-400-1", "장바구니 수량이 올바르지 않습니다"),
	CART_ITEM_QUANTITY_EXCEEDED(ErrorCategory.INVALID, "CART-400-2", "장바구니 수량 한도를 초과했습니다"),
	CART_ITEM_NOT_FOUND(ErrorCategory.NOT_FOUND, "CART-404-1", "장바구니 항목을 찾을 수 없습니다"),
	CART_ITEM_PRODUCT_NOT_FOUND(ErrorCategory.NOT_FOUND, "CART-404-2", "장바구니에 담을 상품을 찾을 수 없습니다"),
	CART_ITEM_PRODUCT_UNAVAILABLE(ErrorCategory.CONFLICT, "CART-409", "장바구니에 담을 수 없는 상품입니다");

	private final ErrorCategory category;
	private final String code;
	private final String message;

	CartErrorCode(ErrorCategory category, String code, String message) {
		this.category = category;
		this.code = code;
		this.message = message;
	}

	@Override
	public ErrorCategory getCategory() {
		return category;
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
