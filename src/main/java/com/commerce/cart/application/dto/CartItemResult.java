package com.commerce.cart.application.result;

import lombok.Builder;
import lombok.Getter;

@Getter
public class CartItemResult {

	private final Long productId;
	private final String name;
	private final int price;
	private final String imageUrl;
	private final int quantity;
	private final int lineAmount;
	private final boolean unavailable;

	@Builder
	private CartItemResult(Long productId, String name, int price, String imageUrl, int quantity, int lineAmount,
		boolean unavailable) {
		this.productId = productId;
		this.name = name;
		this.price = price;
		this.imageUrl = imageUrl;
		this.quantity = quantity;
		this.lineAmount = lineAmount;
		this.unavailable = unavailable;
	}
}
