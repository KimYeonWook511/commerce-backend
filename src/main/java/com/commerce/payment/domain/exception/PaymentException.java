package com.commerce.payment.domain.exception;

import com.commerce.common.exception.CustomException;
import com.commerce.common.exception.ErrorCode;

public class PaymentException extends CustomException {

	public PaymentException(ErrorCode errorCode) {
		super(errorCode);
	}
}
