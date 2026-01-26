package com.commerce.order.controller.request;

import java.util.List;

import com.commerce.order.service.request.OrderCreateItem;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;

@Getter
public class OrderCreateRequest {

	@Valid
	@NotEmpty(message = "주문 상품은 최소 1개 이상이어야 합니다")
	private List<OrderCreateItemRequest> items;

	public List<OrderCreateItem> toServiceRequestItems() {
		return items.stream()
			.map(OrderCreateItemRequest::toServiceItem)
			.toList();
	}
}
