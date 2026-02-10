package com.commerce.order.service.result;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;

import lombok.Builder;
import lombok.Getter;

@Getter
public class OrderCancelResult {

	private Long orderId;
	private OrderStatus status;

	@Builder
	private OrderCancelResult(Long orderId, OrderStatus status) {
		this.orderId = orderId;
		this.status = status;
	}

	public static OrderCancelResult from(Order order) {
		return OrderCancelResult.builder()
			.orderId(order.getId())
			.status(order.getStatus())
			.build();
	}
}
