package com.commerce.order.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

	/**
	 * 결제 전 주문을 취소한다. 결제된 주문은 이 자리로 오지 않는다 — 상태만으로는 취소할 수 있는지가
	 * 정해지지 않고 잔여수량이 남았는지에 따라 전이가 갈려, 검증·계산과 반영으로 나뉜 관문이 받는다.
	 */
	public void cancelBeforePayment() {
		if (this.status != OrderStatus.INIT) {
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
	 * 결제완료 주문만 취소를 받을 수 있다는 것을 검증한다. 통과하면 아무것도 돌려주지 않고, 막히면 던진다.
	 *
	 * <p>이 판정은 취소를 접수하는 트랜잭션이 주문 행을 잠그고 멱등 재생을 판정한 뒤에 불려야 한다.
	 * 같은 요청 키의 환불이 이미 있는 재요청은 이 판정보다 먼저 앞선 결과를 받으므로 이 판정에 닿지
	 * 않는다 — 취소로 종착한 주문이라도 **다른** 요청 키로 오면 그 재생 판정을 지나 여기서 거부된다.
	 * 승인 결과를 모르는 결제를 보는 검사보다도 먼저 불려야 한다 — 결제 전 주문에는 그 검사가 답하는
	 * "결제 확인 중" 안내가 실현될 길이 없기 때문이다.
	 */
	public void checkCancellable() {
		if (this.status != OrderStatus.PAID) {
			throw new OrderException(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED);
		}
	}

	/**
	 * 취소 요청을 검증하고 이번 취소의 값어치를 계산한다. 상태를 바꾸지 않는다.
	 *
	 * <p>줄 목록이 비어 있으면 잔여가 남은 품목만 골라 그 잔여수량으로 채운다. 이미 전부 취소된 품목을
	 * 넣으면 수량 0인 줄이 되어 요청 전체가 거부되고, 품목 식별자를 모르는 호출자가 남은 품목을 영영
	 * 취소하지 못한다.
	 *
	 * <p>주문 상태를 보지 않는다 — 취소 가능 여부는 {@link #checkCancellable()}이 따로 검증한다. 여기에
	 * 상태 가드를 두면 잔여가 하나도 없는 주문이 상태로 먼저 걸려, 확정된 줄이 없는 것을 막는 가드가
	 * 있는지 확인할 수 없게 된다.
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

	/**
	 * 그 환불로 취소한 내역이 이번 요청 줄과 같은지 답한다. 같은 요청 키로 다시 온 요청에 앞 결과를
	 * 돌려줄지 거절할지를 이 판정이 가르며, 줄의 순서는 따지지 않는다.
	 *
	 * <p>요청 줄이 비어 있으면 대조하지 않고 같다고 답한다 — 보낸 목록이 없으니 비교할 내용이 없다.
	 * 반대로 저장된 내역이 하나도 없는데 요청 줄이 있으면 다르다고 답한다. 취소 품목 내역을 남기기
	 * 전에 열린 환불이 그런 모습인데, 그 환불이 무엇을 취소했는지 알 수 없으므로 같다고 판정하지 않는다.
	 */
	public boolean matchesCancellation(Long refundId, List<OrderCancelLine> requestedLines) {
		if (requestedLines == null || requestedLines.isEmpty()) {
			return true;
		}

		Map<Long, Integer> recordedQuantities = new HashMap<>();
		for (OrderItem orderItem : this.orderItems) {
			int quantity = orderItem.cancelledQuantityFor(refundId);
			if (quantity > 0) {
				recordedQuantities.put(orderItem.getId(), quantity);
			}
		}
		if (recordedQuantities.size() != requestedLines.size()) {
			return false;
		}
		return requestedLines.stream()
			.allMatch(line -> Integer.valueOf(line.quantity()).equals(recordedQuantities.get(line.orderItemId())));
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
