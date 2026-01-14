package com.commerce.product.exception;

import com.commerce.common.exception.CustomException;
import com.commerce.common.exception.ErrorCode;

public class ProductException extends CustomException {

	public ProductException(ErrorCode errorCode) {
		super(errorCode);
	}
}
