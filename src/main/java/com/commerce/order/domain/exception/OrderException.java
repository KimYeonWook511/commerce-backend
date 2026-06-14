package com.commerce.order.domain.exception;

import com.commerce.common.exception.CustomException;
import com.commerce.common.exception.ErrorCode;

public class OrderException extends CustomException {

	public OrderException(ErrorCode errorCode) {
		super(errorCode);
	}
}
