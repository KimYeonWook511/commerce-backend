package com.commerce.order.presentation.http.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;

@Getter
public class OrderCreateItemRequest {

	@NotNull(message = "상품 ID는 필수입니다")
	@Positive(message = "상품 ID는 양수여야 합니다")
	private Long productId;

	@NotNull(message = "수량은 필수입니다")
	@Positive(message = "수량은 양수여야 합니다")
	private Integer quantity;
}
