package com.commerce.cart.presentation.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
public class CartItemUpdateRequest {

	@NotNull(message = "수량은 필수입니다")
	@Min(value = 1, message = "수량은 1 이상이어야 합니다")
	@Max(value = 99, message = "수량은 99 이하여야 합니다")
	private Integer quantity;
}
