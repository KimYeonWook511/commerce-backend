package com.commerce.auth.domain.exception;

import com.commerce.common.exception.CustomException;
import com.commerce.common.exception.ErrorCode;

public class AuthException extends CustomException {

	public AuthException(ErrorCode errorCode) {
		super(errorCode);
	}
}
