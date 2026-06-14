package com.commerce.cart.application.dto;

import java.util.List;

import lombok.Builder;
import lombok.Getter;

@Getter
public class CartResult {

	private final List<CartItemResult> items;
	private final int totalAmount;

	@Builder
	private CartResult(List<CartItemResult> items, int totalAmount) {
		this.items = items;
		this.totalAmount = totalAmount;
	}
}
