package com.commerce.order.domain;

import java.util.ArrayList;
import java.util.List;

import com.commerce.common.exception.CommonErrorCode;
import com.commerce.common.exception.CommonException;
import com.commerce.common.jpa.BaseTimeEntity;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "tbl_order_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class OrderItem extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "order_id", nullable = false, foreignKey = @ForeignKey(name = "fk_order_item_order_id"))
	private Order order;

	@Column(name = "product_id", nullable = false)
	private Long productId;

	@Column(nullable = false)
	private int quantity;

	@Column(nullable = false)
	private int unitPrice;

	/** 지금까지 취소된 수량의 누계. 오르기만 하며 주문수량을 넘지 않는다 */
	@Column(name = "cancelled_quantity", nullable = false)
	private int cancelledQuantity;

	@OneToMany(mappedBy = "orderItem", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<OrderItemCancellation> cancellations = new ArrayList<>();

	private OrderItem(Order order, Long productId, int quantity, int unitPrice) {
		this.order = order;
		this.productId = productId;
		this.quantity = quantity;
		this.unitPrice = unitPrice;
	}

	public static OrderItem of(Order order, Long productId, int quantity, int unitPrice) {
		return new OrderItem(order, productId, quantity, unitPrice);
	}

	/** 아직 취소되지 않고 남은 수량. 계산해 답하고 저장하지 않는다 — 저장하면 원본과 갈라질 자리가 생긴다 */
	public int remainingQuantity() {
		return quantity - cancelledQuantity;
	}

	/**
	 * 이 수량만큼 취소할 수 있는지 본다. 상태를 바꾸지 않아, 한 줄이라도 걸리면 전부 거부하는 판정을
	 * 반영 전에 모든 줄에 돌릴 수 있다.
	 */
	void checkCancellable(int requestedQuantity) {
		if (requestedQuantity < 1) {
			throw new CommonException(CommonErrorCode.INVALID_REQUEST);
		}
		if (requestedQuantity > remainingQuantity()) {
			throw new OrderException(OrderErrorCode.ORDER_CANCEL_QUANTITY_EXCEEDED);
		}
	}

	/** 이 수량을 취소했을 때의 값어치. 단가가 주문 시점 사본이라 상품 판매가가 바뀌어도 달라지지 않는다 */
	int cancellationValue(int requestedQuantity) {
		return unitPrice * requestedQuantity;
	}

	/** 그 환불로 이 품목을 몇 개 취소했나. 그 환불의 내역이 없으면 0이다 */
	int cancelledQuantityFor(Long refundId) {
		return this.cancellations.stream()
			.filter(cancellation -> cancellation.getRefundId().equals(refundId))
			.mapToInt(OrderItemCancellation::getQuantity)
			.sum();
	}

	/**
	 * 취소수량을 올리고 그 내역을 남긴다. 둘을 한 자리에서만 움직여야 내역 수량의 합과 취소수량이
	 * 갈라지지 않는다.
	 */
	void cancel(int requestedQuantity, Long refundId) {
		checkCancellable(requestedQuantity);
		this.cancelledQuantity += requestedQuantity;
		this.cancellations.add(OrderItemCancellation.of(this, refundId, requestedQuantity));
	}
}
