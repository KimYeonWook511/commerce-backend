package com.commerce.stock.exception;

import org.springframework.http.HttpStatus;

import com.commerce.common.exception.ErrorCode;

public enum StockErrorCode implements ErrorCode {
	STOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "STOCK-404", "재고 정보를 찾을 수 없습니다"),
	OUT_OF_STOCK(HttpStatus.CONFLICT, "STOCK-409", "재고가 부족합니다"),
	OPTIMISTIC_LOCK_FAILED(HttpStatus.CONFLICT, "STOCK-409-1", "재고 차감 재시도에 실패했습니다"),
	INVALID_QUANTITY(HttpStatus.BAD_REQUEST, "STOCK-400", "차감 수량이 올바르지 않습니다");

	private final HttpStatus status;
	private final String code;
	private final String message;

	StockErrorCode(HttpStatus status, String code, String message) {
		this.status = status;
		this.code = code;
		this.message = message;
	}

	@Override
	public HttpStatus getStatus() {
		return status;
	}

	@Override
	public String getCode() {
		return code;
	}

	@Override
	public String getMessage() {
		return message;
	}
}
