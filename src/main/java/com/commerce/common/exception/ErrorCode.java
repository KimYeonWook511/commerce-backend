package com.commerce.common.exception;

public interface ErrorCode {
	
	ErrorCategory getCategory();

	String getCode();

	String getMessage();
}
