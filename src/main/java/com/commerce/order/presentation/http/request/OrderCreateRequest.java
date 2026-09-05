package com.commerce.order.presentation.http.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
public class OrderCreateRequest {

	@Valid
	@NotEmpty(message = "주문 상품은 최소 1개 이상이어야 합니다")
	private List<@NotNull(message = "주문 상품은 비어 있을 수 없습니다") OrderCreateItemRequest> items;
}
