package com.commerce.order.service.request;

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
