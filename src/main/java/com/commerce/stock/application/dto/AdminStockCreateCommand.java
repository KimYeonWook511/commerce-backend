package com.commerce.stock.application.dto;

import com.commerce.stock.domain.StockAdjustmentReason;

import lombok.Builder;
import lombok.Getter;

@Getter
public class AdminStockCreateCommand {

	private Long productId;
	private int quantity;
	private StockAdjustmentReason reason;
	private Long adminMemberId;

	@Builder
	private AdminStockCreateCommand(Long productId, int quantity, StockAdjustmentReason reason, Long adminMemberId) {
		this.productId = productId;
		this.quantity = quantity;
		this.reason = reason;
		this.adminMemberId = adminMemberId;
	}
}
