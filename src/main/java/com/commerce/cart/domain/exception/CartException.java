package com.commerce.cart.domain.exception;

import com.commerce.common.exception.CustomException;
import com.commerce.common.exception.ErrorCode;

public class CartException extends CustomException {

	public CartException(ErrorCode errorCode) {
		super(errorCode);
	}
}
