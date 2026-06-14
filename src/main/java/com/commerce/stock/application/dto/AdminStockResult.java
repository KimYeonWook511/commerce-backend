package com.commerce.stock.application.dto;

import com.commerce.stock.domain.Stock;

import lombok.Builder;
import lombok.Getter;

@Getter
public class AdminStockResult {

	private Long productId;
	private Long stockId;
	private int quantity;

	@Builder
	private AdminStockResult(Long productId, Long stockId, int quantity) {
		this.productId = productId;
		this.stockId = stockId;
		this.quantity = quantity;
	}

	public static AdminStockResult from(Stock stock) {
		return AdminStockResult.builder()
			.productId(stock.getProductId())
			.stockId(stock.getId())
			.quantity(stock.getQuantity())
			.build();
	}
}
