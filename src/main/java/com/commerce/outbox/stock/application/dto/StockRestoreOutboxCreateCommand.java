package com.commerce.outbox.stock.application.dto;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Builder;
import lombok.Getter;

@Getter
public class StockRestoreOutboxCreateCommand {

	private Long orderId;
	private List<Item> items;
	private LocalDateTime requestedAt;

	@Builder
	private StockRestoreOutboxCreateCommand(Long orderId, List<Item> items, LocalDateTime requestedAt) {
		this.orderId = orderId;
		this.items = items;
		this.requestedAt = requestedAt;
	}

	@Getter
	public static class Item {
		private Long productId;
		private int quantity;

		@Builder
		private Item(Long productId, int quantity) {
			this.productId = productId;
			this.quantity = quantity;
		}
	}
}
