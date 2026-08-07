package com.commerce.order.application.dto;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;

import lombok.Builder;
import lombok.Getter;

@Getter
public class OrderCancelResult {

	private Long orderId;
	private OrderStatus status;
	/** 환불 진행 상태. INIT 취소는 NONE, PAID 취소는 COMPLETED 또는 IN_PROGRESS. */
	private OrderCancelRefundStatus refundStatus;

	@Builder
	private OrderCancelResult(Long orderId, OrderStatus status, OrderCancelRefundStatus refundStatus) {
		this.orderId = orderId;
		this.status = status;
		this.refundStatus = refundStatus;
	}

	public static OrderCancelResult from(Order order) {
		return OrderCancelResult.builder()
			.orderId(order.getId())
			.status(order.getStatus())
			.refundStatus(OrderCancelRefundStatus.NONE)
			.build();
	}

	public static OrderCancelResult withRefundStatus(Order order, OrderCancelRefundStatus refundStatus) {
		return OrderCancelResult.builder()
			.orderId(order.getId())
			.status(order.getStatus())
			.refundStatus(refundStatus)
			.build();
	}
}
