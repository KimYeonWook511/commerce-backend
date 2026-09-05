package com.commerce.order.domain;

import java.util.List;

/**
 * 검증을 마친 취소 대상 줄들과 이번 취소의 값어치.
 *
 * <p>주문이 만들어 주문에게 돌아오는 값이라 주문 도메인 밖에서는 조립할 수 없다. 반영 관문이 이 타입만
 * 받으므로, 검증을 지나지 않은 줄이 반영으로 들어가는 길이 없다.
 */
public final class OrderCancelPlan {

	private final List<OrderCancelLine> lines;
	private final int cancelAmount;

	private OrderCancelPlan(List<OrderCancelLine> lines, int cancelAmount) {
		this.lines = lines;
		this.cancelAmount = cancelAmount;
	}

	static OrderCancelPlan of(List<OrderCancelLine> lines, int cancelAmount) {
		return new OrderCancelPlan(List.copyOf(lines), cancelAmount);
	}

	public List<OrderCancelLine> lines() {
		return lines;
	}

	public int cancelAmount() {
		return cancelAmount;
	}
}
