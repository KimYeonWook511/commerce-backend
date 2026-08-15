package com.commerce.order.application.dto;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;

import lombok.Builder;
import lombok.Getter;

@Getter
public class OrderCancelResult {

	private Long orderId;
	private OrderStatus status;
	/** 환불 진행 상태. 결제 전 취소는 NONE, 결제된 주문 취소는 COMPLETED 또는 IN_PROGRESS. */
	private OrderCancelRefundStatus refundStatus;
	/** 이번 요청으로 환불되는 금액. 그 결제의 모든 환불 합이 아니라 이번 건 하나다 */
	private int refundedAmount;
	/** 앞으로 더 취소할 수 있는 금액. 승인 금액에서 누적 환불액을 뺀 값이다 */
	private int remainingAmount;

	@Builder
	private OrderCancelResult(
		Long orderId,
		OrderStatus status,
		OrderCancelRefundStatus refundStatus,
		int refundedAmount,
		int remainingAmount
	) {
		this.orderId = orderId;
		this.status = status;
		this.refundStatus = refundStatus;
		this.refundedAmount = refundedAmount;
		this.remainingAmount = remainingAmount;
	}

	/**
	 * 결제 전 취소. 되돌릴 돈이 없어 환불액이 0이고, 승인 금액이 없어 한도 자체가 없으므로 남은 금액도
	 * 0이다.
	 */
	public static OrderCancelResult from(Order order) {
		return OrderCancelResult.builder()
			.orderId(order.getId())
			.status(order.getStatus())
			.refundStatus(OrderCancelRefundStatus.NONE)
			.refundedAmount(0)
			.remainingAmount(0)
			.build();
	}

	public static OrderCancelResult withRefund(
		Order order,
		OrderCancelRefundStatus refundStatus,
		int refundedAmount,
		int remainingAmount
	) {
		return OrderCancelResult.builder()
			.orderId(order.getId())
			.status(order.getStatus())
			.refundStatus(refundStatus)
			.refundedAmount(refundedAmount)
			.remainingAmount(remainingAmount)
			.build();
	}
}
