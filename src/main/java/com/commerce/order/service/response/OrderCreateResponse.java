package com.commerce.order.service.response;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;

import lombok.Builder;
import lombok.Getter;

@Getter
public class OrderCreateResponse {

	private Long orderId;
	private int totalPrice;
	private OrderStatus status;

	@Builder
	private OrderCreateResponse(Long orderId, int totalPrice, OrderStatus status) {
		this.orderId = orderId;
		this.totalPrice = totalPrice;
		this.status = status;
	}

	public static OrderCreateResponse from(Order order) {
		return OrderCreateResponse.builder()
			.orderId(order.getId())
			.totalPrice(order.getTotalPrice())
			.status(order.getStatus())
			.build();
	}
}
