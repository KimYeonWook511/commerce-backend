package com.commerce.order.domain;

import java.util.ArrayList;
import java.util.List;

import com.commerce.common.jpa.BaseTimeEntity;
import com.commerce.member.domain.Member;
import com.commerce.product.domain.Product;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
	private Long version;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Column(nullable = false)
	private int totalPrice;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false)
	private OrderStatus status;

	@Column(unique = true)
	private String merchantPayKey;

	@Column
	private String idempotencyKey;

	@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<OrderItem> orderItems = new ArrayList<>();

	@Builder
	private Order(Member member, OrderStatus status, String idempotencyKey) {
		this.member = member;
		this.status = status;
		this.idempotencyKey = idempotencyKey;
		this.totalPrice = 0;
	}

	public static Order create(Member member) {
		return Order.builder()
			.member(member)
			.status(OrderStatus.INIT)
			.build();
	}

	public static Order create(Member member, String idempotencyKey) {
		return Order.builder()
			.member(member)
			.status(OrderStatus.INIT)
			.idempotencyKey(idempotencyKey)
			.build();
	}

	public void addOrderItem(Product product, int quantity) {
		OrderItem orderItem = OrderItem.of(this, product, quantity);
		this.orderItems.add(orderItem);
		this.totalPrice += product.getPrice() * quantity;
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

	public void assignMerchantPayKey(String merchantPayKey) {
		if (this.merchantPayKey == null) {
			this.merchantPayKey = merchantPayKey;
		}
	}

}
