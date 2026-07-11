package com.commerce.product.domain.exception;

import com.commerce.common.exception.ErrorCategory;
import com.commerce.common.exception.ErrorCode;

public enum ProductErrorCode implements ErrorCode {
	PRODUCT_NOT_FOUND(ErrorCategory.NOT_FOUND, "PRODUCT-404", "상품을 찾을 수 없습니다");

	private final ErrorCategory category;
	private final String code;
	private final String message;

	ProductErrorCode(ErrorCategory category, String code, String message) {
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
