package com.commerce.stock.domain.exception;

import com.commerce.common.exception.CustomException;
import com.commerce.common.exception.ErrorCode;

public class StockException extends CustomException {

	public StockException(ErrorCode errorCode) {
		super(errorCode);
	}
}
