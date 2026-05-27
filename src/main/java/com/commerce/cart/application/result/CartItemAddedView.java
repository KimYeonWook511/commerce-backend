package com.commerce.cart.application.result;

import com.commerce.cart.domain.CartItem;

import lombok.Builder;
import lombok.Getter;

@Getter
public class CartItemAddedView {

	private final Long productId;
	private final int quantity;

	@Builder
	private CartItemAddedView(Long productId, int quantity) {
		this.productId = productId;
		this.quantity = quantity;
	}

	public static CartItemAddedView from(CartItem cartItem) {
		return CartItemAddedView.builder()
			.productId(cartItem.getProductId())
			.quantity(cartItem.getQuantity())
			.build();
	}
}
