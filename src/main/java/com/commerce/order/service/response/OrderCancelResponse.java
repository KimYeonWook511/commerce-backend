package com.commerce.order.service.response;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;

import lombok.Builder;
import lombok.Getter;

@Getter
public class OrderCancelResponse {

	private Long orderId;
	private OrderStatus status;

	@Builder
	private OrderCancelResponse(Long orderId, OrderStatus status) {
		this.orderId = orderId;
		this.status = status;
	}

	public static OrderCancelResponse from(Order order) {
		return OrderCancelResponse.builder()
			.orderId(order.getId())
			.status(order.getStatus())
			.build();
	}
}
