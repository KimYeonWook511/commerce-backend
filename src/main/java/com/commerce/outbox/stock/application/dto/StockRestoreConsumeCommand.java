package com.commerce.outbox.stock.application.dto;

import java.util.List;

import lombok.Builder;
import lombok.Getter;

@Getter
public class StockRestoreConsumeCommand {

	private String eventId;
	private List<Item> items;

	@Builder
	private StockRestoreConsumeCommand(String eventId, List<Item> items) {
		this.eventId = eventId;
		this.items = items;
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
