package com.commerce.order.presentation.http.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;

@Getter
public class OrderCancelItemRequest {

	@NotNull(message = "주문 상품 ID는 필수입니다")
	@Positive(message = "주문 상품 ID는 양수여야 합니다")
	private Long orderItemId;

	@NotNull(message = "취소 수량은 필수입니다")
	@Positive(message = "취소 수량은 1 이상이어야 합니다")
	private Integer quantity;
}
