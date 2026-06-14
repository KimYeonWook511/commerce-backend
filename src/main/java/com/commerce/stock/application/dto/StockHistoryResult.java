package com.commerce.stock.application.result;

import java.time.LocalDateTime;

import com.commerce.stock.domain.StockAdjustmentReason;
import com.commerce.stock.domain.StockHistory;

import lombok.Builder;
import lombok.Getter;

@Getter
public class StockHistoryResult {

	private Long historyId;
	private Long productId;
	private Long stockId;
	private int quantityChange;
	private StockAdjustmentReason reason;
	private Long adminMemberId;
	private LocalDateTime createdAt;

	@Builder
	private StockHistoryResult(
		Long historyId,
		Long productId,
		Long stockId,
		int quantityChange,
		StockAdjustmentReason reason,
		Long adminMemberId,
		LocalDateTime createdAt
	) {
		this.historyId = historyId;
		this.productId = productId;
		this.stockId = stockId;
		this.quantityChange = quantityChange;
		this.reason = reason;
		this.adminMemberId = adminMemberId;
		this.createdAt = createdAt;
	}

	public static StockHistoryResult from(StockHistory history, Long productId) {
		return StockHistoryResult.builder()
			.historyId(history.getId())
			.productId(productId)
			.stockId(history.getStockId())
			.quantityChange(history.getQuantityChange())
			.reason(history.getReason())
			.adminMemberId(history.getAdminMemberId())
			.createdAt(history.getCreatedAt())
			.build();
	}
}
