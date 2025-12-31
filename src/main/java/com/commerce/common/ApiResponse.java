package com.commerce.common;

import com.commerce.common.exception.ErrorCode;
import lombok.Getter;

@Getter
public class ApiResponse<T> {

	private final String code;
	private final String message;
	private final T data;

	private ApiResponse(String code, String message, T data) {
		this.code = code;
		this.message = message;
		this.data = data;
	}

	public static <T> ApiResponse<T> of(T data) {
		return new ApiResponse<>("SUCCESS", "OK", data);
	}

	public static <T> ApiResponse<T> error(ErrorCode errorCode) {
		return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), null);
	}

	public static <T> ApiResponse<T> error(ErrorCode errorCode, T data) {
		return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), data);
	}

}
