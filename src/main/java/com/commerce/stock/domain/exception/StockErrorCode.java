package com.commerce.stock.domain.exception;

import com.commerce.common.exception.ErrorCategory;
import com.commerce.common.exception.ErrorCode;

public enum StockErrorCode implements ErrorCode {
	STOCK_NOT_FOUND(ErrorCategory.NOT_FOUND, "STOCK-404", "재고 정보를 찾을 수 없습니다"),
	OUT_OF_STOCK(ErrorCategory.CONFLICT, "STOCK-409", "재고가 부족합니다"),
	STOCK_ALREADY_EXISTS(ErrorCategory.CONFLICT, "STOCK-409-2", "이미 재고 정보가 존재합니다"),
	OPTIMISTIC_LOCK_FAILED(ErrorCategory.CONFLICT, "STOCK-409-1", "재고 차감 재시도에 실패했습니다"),
	INVALID_STOCK_QUANTITY(ErrorCategory.INVALID, "STOCK-400-3", "재고 수량이 올바르지 않습니다"),
	INVALID_DECREASE_QUANTITY(ErrorCategory.INVALID, "STOCK-400", "차감 수량이 올바르지 않습니다"),
	INVALID_INCREASE_QUANTITY(ErrorCategory.INVALID, "STOCK-400-1", "증가 수량이 올바르지 않습니다"),
	INVALID_HISTORY_QUANTITY_CHANGE(ErrorCategory.INVALID, "STOCK-400-2", "재고 이력 변경 수량이 올바르지 않습니다");

	private final ErrorCategory category;
	private final String code;
	private final String message;

	StockErrorCode(ErrorCategory category, String code, String message) {
		this.category = category;
		this.code = code;
		this.message = message;
	}

	@Override
	public ErrorCategory getCategory() {
		return category;
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
