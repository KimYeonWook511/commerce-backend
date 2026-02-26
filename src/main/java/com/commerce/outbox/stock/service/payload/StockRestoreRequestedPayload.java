package com.commerce.outbox.stock.service.payload;

import java.util.Comparator;
import java.util.List;

import com.commerce.outbox.stock.service.command.StockRestoreOutboxCreateCommand;

import lombok.Builder;
import lombok.Getter;

@Getter
public class StockRestoreRequestedPayload {

	private Long orderId;
	private List<Item> items;

	@Builder
	private StockRestoreRequestedPayload(Long orderId, List<Item> items) {
		this.orderId = orderId;
		this.items = items;
	}

	public static StockRestoreRequestedPayload from(StockRestoreOutboxCreateCommand command) {
		List<Item> sortedItems = command.getItems().stream()
			.map(item -> Item.builder()
				.productId(item.getProductId())
				.quantity(item.getQuantity())
				.build())
			.sorted(Comparator.comparing(Item::getProductId))
			.toList();

		return StockRestoreRequestedPayload.builder()
			.orderId(command.getOrderId())
			.items(sortedItems)
			.build();
	}

	public boolean hasValidItems() {
		if (items == null || items.isEmpty()) {
			return false;
		}

		for (Item item : items) {
			if (item == null || item.getProductId() == null || item.getQuantity() <= 0) {
				return false;
			}
		}
		return true;
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
