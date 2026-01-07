package com.commerce.stock.exception;

import com.commerce.common.exception.CustomException;
import com.commerce.common.exception.ErrorCode;

public class StockException extends CustomException {

	public StockException(ErrorCode errorCode) {
		super(errorCode);
	}
}
