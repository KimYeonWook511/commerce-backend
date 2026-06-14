package com.commerce.order.application.command;

import lombok.Builder;
import lombok.Getter;

@Getter
public class OrderCreateItem {

	private Long productId;
	private int quantity;

	@Builder
	private OrderCreateItem(Long productId, int quantity) {
		this.productId = productId;
		this.quantity = quantity;
	}
}
