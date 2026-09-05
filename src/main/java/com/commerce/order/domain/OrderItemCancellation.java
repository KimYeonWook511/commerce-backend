package com.commerce.order.domain;

import java.util.Objects;

import com.commerce.common.exception.CommonErrorCode;
import com.commerce.common.exception.CommonException;
import com.commerce.common.jpa.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 취소 한 번이 어느 주문 품목을 몇 개 취소했는지. 주문 품목의 자식이며 그 취소로 열린 환불은
 * 다른 aggregate의 루트라 식별자로만 가리킨다.
 *
 * <p>주문 품목의 취소수량은 누계로만 쌓여 어느 요청이 무엇을 취소했는지 답하지 못하고, 금액에서
 * 역산하면 단가가 같은 조합을 구분하지 못한다. 환불이 자동으로 끝나지 않아 사람이 이어받을 때
 * 이 줄이 유일한 복구 근거다.
 */
@Entity
@Table(
	name = "tbl_order_item_cancellation",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_order_item_cancellation_refund_item",
			columnNames = {"refund_id", "order_item_id"})
	},
	indexes = {
		@Index(name = "idx_order_item_cancellation_refund", columnList = "refund_id")
	}
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class OrderItemCancellation extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "order_item_id", nullable = false,
		foreignKey = @ForeignKey(name = "fk_order_item_cancellation_order_item"))
	private OrderItem orderItem;

	@Column(name = "refund_id", nullable = false)
	private Long refundId;

	@Column(nullable = false)
	private int quantity;

	private OrderItemCancellation(OrderItem orderItem, Long refundId, int quantity) {
		this.orderItem = orderItem;
		this.refundId = refundId;
		this.quantity = quantity;
	}

	/**
	 * 생성 관문. 취소수량을 올리는 자리에서만 부르도록 주문 도메인 안에 가둔다 — 두 값이 다른 자리에서
	 * 움직이면 내역 수량의 합과 취소수량이 갈라진다.
	 */
	static OrderItemCancellation of(OrderItem orderItem, Long refundId, int quantity) {
		Objects.requireNonNull(orderItem, "orderItem must not be null");
		Objects.requireNonNull(refundId, "refundId must not be null");
		if (quantity < 1) {
			throw new CommonException(CommonErrorCode.INVALID_REQUEST);
		}
		return new OrderItemCancellation(orderItem, refundId, quantity);
	}
}
