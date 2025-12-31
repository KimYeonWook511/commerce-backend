package com.commerce.common.exception;

public class CommonException extends CustomException {

	public CommonException(ErrorCode errorCode) {
		super(errorCode);
	}
}
