package com.commerce.order.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.commerce.common.exception.CommonErrorCode;
import com.commerce.common.exception.CommonException;
import com.commerce.common.jpa.BaseTimeEntity;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "tbl_order", uniqueConstraints = {
	@UniqueConstraint(name = "uk_order_member_idempotency", columnNames = {"member_id", "idempotency_key"})
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class Order extends BaseTimeEntity {

	@Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

	@Version
	@Column(nullable = false)
	private Long version;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(nullable = false)
	private int totalPrice;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false)
	private OrderStatus status;

	@Column
	private String idempotencyKey;

	@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<OrderItem> orderItems = new ArrayList<>();

	private Order(Long memberId, OrderStatus status, String idempotencyKey) {
		this.memberId = memberId;
		this.status = status;
		this.idempotencyKey = idempotencyKey;
		this.totalPrice = 0;
	}

	public static Order create(Long memberId) {
		return new Order(memberId, OrderStatus.INIT, null);
	}

	public static Order create(Long memberId, String idempotencyKey) {
		return new Order(memberId, OrderStatus.INIT, idempotencyKey);
	}

	public void addOrderItem(Long productId, int quantity, int unitPrice) {
		OrderItem orderItem = OrderItem.of(this, productId, quantity, unitPrice);
		this.orderItems.add(orderItem);
		this.totalPrice += unitPrice * quantity;
	}

	public void cancel() {
		if (this.status != OrderStatus.INIT && this.status != OrderStatus.PAID) {
			throw new OrderException(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED);
		}

		this.status = OrderStatus.CANCELED;
	}

	public void completePayment() {
		if (this.status == OrderStatus.PAID) {
			throw new OrderException(OrderErrorCode.ORDER_ALREADY_PAID);
		}
		if (this.status == OrderStatus.CANCELED) {
			throw new OrderException(OrderErrorCode.ORDER_CANCELED_FOR_PAYMENT);
		}
		if (this.status != OrderStatus.INIT) {
			throw new OrderException(OrderErrorCode.ORDER_INVALID_STATE_FOR_PAYMENT);
		}

		this.status = OrderStatus.PAID;
	}

	public void checkPayable() {
		if (this.status != OrderStatus.INIT) {
			throw new OrderException(OrderErrorCode.ORDER_PAYMENT_NOT_ALLOWED);
		}
	}

	/**
	 * 취소 요청을 검증하고 이번 취소의 값어치를 계산한다. 상태를 바꾸지 않는다.
	 *
	 * <p>줄 목록이 비어 있으면 잔여가 남은 품목만 골라 그 잔여수량으로 채운다. 이미 전부 취소된 품목을
	 * 넣으면 수량 0인 줄이 되어 요청 전체가 거부되고, 품목 식별자를 모르는 호출자가 남은 품목을 영영
	 * 취소하지 못한다.
	 *
	 * <p>주문 상태를 보지 않는다 — 상태 확인은 취소를 접수하는 흐름의 몫이다. 여기에 상태 가드를 두면
	 * 잔여가 하나도 없는 주문이 상태로 먼저 걸려, 확정된 줄이 없는 것을 막는 가드가 있는지 확인할 수
	 * 없게 된다.
	 */
	public OrderCancelPlan planCancellation(List<OrderCancelLine> requestedLines) {
		List<OrderCancelLine> targetLines = (requestedLines == null || requestedLines.isEmpty())
			? remainingLines()
			: verifiedLines(requestedLines);

		if (targetLines.isEmpty()) {
			throw new OrderException(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED);
		}

		int cancelAmount = 0;
		for (OrderCancelLine line : targetLines) {
			cancelAmount += findOrderItem(line.orderItemId()).cancellationValue(line.quantity());
		}
		return OrderCancelPlan.of(targetLines, cancelAmount);
	}

	/**
	 * 검증·계산이 확정한 줄대로 취소수량을 올리고 내역을 남긴다. 모든 잔여가 0이 되면 그때만 취소로
	 * 전이한다.
	 *
	 * <p>여기서도 모든 줄을 먼저 훑어 확인한 뒤에 반영한다. 트랜잭션 롤백이 결과적으로 같은 것을
	 * 보장하지만 그것은 인프라이고, 한 줄이라도 걸리면 전부 거부하는 것은 주문의 규칙이라 aggregate
	 * 안에서 성립해야 한다.
	 */
	public void applyCancellation(OrderCancelPlan plan, Long refundId) {
		Objects.requireNonNull(plan, "plan must not be null");
		Objects.requireNonNull(refundId, "refundId must not be null");

		for (OrderCancelLine line : plan.lines()) {
			findOrderItem(line.orderItemId()).checkCancellable(line.quantity());
		}
		for (OrderCancelLine line : plan.lines()) {
			findOrderItem(line.orderItemId()).cancel(line.quantity(), refundId);
		}

		if (isFullyCancelled()) {
			this.status = OrderStatus.CANCELED;
		}
	}

	private List<OrderCancelLine> remainingLines() {
		return this.orderItems.stream()
			.filter(orderItem -> orderItem.remainingQuantity() > 0)
			.map(orderItem -> new OrderCancelLine(orderItem.getId(), orderItem.remainingQuantity()))
			.toList();
	}

	private List<OrderCancelLine> verifiedLines(List<OrderCancelLine> requestedLines) {
		for (OrderCancelLine line : requestedLines) {
			findOrderItem(line.orderItemId()).checkCancellable(line.quantity());
		}
		return List.copyOf(requestedLines);
	}

	private OrderItem findOrderItem(Long orderItemId) {
		if (orderItemId == null) {
			throw new CommonException(CommonErrorCode.INVALID_REQUEST);
		}
		return this.orderItems.stream()
			.filter(orderItem -> orderItemId.equals(orderItem.getId()))
			.findFirst()
			.orElseThrow(() -> new CommonException(CommonErrorCode.INVALID_REQUEST));
	}

	private boolean isFullyCancelled() {
		return this.orderItems.stream().allMatch(orderItem -> orderItem.remainingQuantity() == 0);
	}

}
