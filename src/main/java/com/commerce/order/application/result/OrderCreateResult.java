package com.commerce.order.application.result;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;

import lombok.Builder;
import lombok.Getter;

@Getter
public class OrderCreateResult {

	private Long orderId;
	private int totalPrice;
	private OrderStatus status;

	@Builder
	private OrderCreateResult(Long orderId, int totalPrice, OrderStatus status) {
		this.orderId = orderId;
		this.totalPrice = totalPrice;
		this.status = status;
	}

	public static OrderCreateResult from(Order order) {
		return OrderCreateResult.builder()
			.orderId(order.getId())
			.totalPrice(order.getTotalPrice())
			.status(order.getStatus())
			.build();
	}
}
