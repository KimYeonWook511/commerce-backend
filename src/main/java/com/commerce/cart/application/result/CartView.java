package com.commerce.cart.application.result;

import java.util.List;

import lombok.Builder;
import lombok.Getter;

@Getter
public class CartView {

	private final List<CartItemView> items;
	private final int totalAmount;

	@Builder
	private CartView(List<CartItemView> items, int totalAmount) {
		this.items = items;
		this.totalAmount = totalAmount;
	}
}
