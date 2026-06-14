package com.commerce.product.domain.exception;

import com.commerce.common.exception.CustomException;
import com.commerce.common.exception.ErrorCode;

public class ProductException extends CustomException {

	public ProductException(ErrorCode errorCode) {
		super(errorCode);
	}
}
