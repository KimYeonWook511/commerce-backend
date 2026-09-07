package com.commerce.order.application.dto;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;

import lombok.Builder;
import lombok.Getter;

@Getter
public class OrderCancelResult {

	private Long orderId;
	private OrderStatus status;
	/** 환불 진행 상태. COMPLETED 또는 IN_PROGRESS다. */
	private OrderCancelRefundStatus refundStatus;
	/** 이번 요청으로 환불되는 금액. 그 결제의 모든 환불 합이 아니라 이번 건 하나다 */
	private int refundedAmount;
	/** 앞으로 더 취소할 수 있는 금액. 승인 금액에서 돌려주기로 한 금액을 뺀 값이다 */
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
