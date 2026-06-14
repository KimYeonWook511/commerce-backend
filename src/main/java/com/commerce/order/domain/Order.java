package com.commerce.order.domain;

import java.util.ArrayList;
import java.util.List;

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
import lombok.Builder;
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

	@Builder
	private Order(Long memberId, OrderStatus status, String idempotencyKey) {
		this.memberId = memberId;
		this.status = status;
		this.idempotencyKey = idempotencyKey;
		this.totalPrice = 0;
	}

	public static Order create(Long memberId) {
		return Order.builder()
			.memberId(memberId)
			.status(OrderStatus.INIT)
			.build();
	}

	public static Order create(Long memberId, String idempotencyKey) {
		return Order.builder()
			.memberId(memberId)
			.status(OrderStatus.INIT)
			.idempotencyKey(idempotencyKey)
			.build();
	}

	public void addOrderItem(Long productId, int quantity, int unitPrice) {
		OrderItem orderItem = OrderItem.of(this, productId, quantity, unitPrice);
		this.orderItems.add(orderItem);
		this.totalPrice += unitPrice * quantity;
	}

	public void cancel() {
		if (this.status != OrderStatus.INIT) {
			throw new OrderException(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED);
		}

		this.status = OrderStatus.CANCELED;
	}

	public void completePayment() {
		if (this.status != OrderStatus.INIT) {
			throw new OrderException(OrderErrorCode.ORDER_PAID_NOT_ALLOWED);
		}

		this.status = OrderStatus.PAID;
	}

	public void checkPayable() {
		if (this.status != OrderStatus.INIT) {
			throw new OrderException(OrderErrorCode.ORDER_PAYMENT_NOT_ALLOWED);
		}
	}

}
