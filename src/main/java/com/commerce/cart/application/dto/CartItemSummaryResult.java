package com.commerce.cart.application.result;

import com.commerce.cart.domain.CartItem;

import lombok.Builder;
import lombok.Getter;

@Getter
public class CartItemSummaryResult {

	private final Long productId;
	private final int quantity;

	@Builder
	private CartItemSummaryResult(Long productId, int quantity) {
		this.productId = productId;
		this.quantity = quantity;
	}

	public static CartItemSummaryResult from(CartItem cartItem) {
		return CartItemSummaryResult.builder()
			.productId(cartItem.getProductId())
			.quantity(cartItem.getQuantity())
			.build();
	}
}
