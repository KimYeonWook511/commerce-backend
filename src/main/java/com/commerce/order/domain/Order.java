package com.commerce.order.domain;

import java.util.ArrayList;
import java.util.List;

import com.commerce.member.domain.Member;
import com.commerce.orderitem.domain.OrderItem;
import com.commerce.product.domain.Product;

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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "tbl_order")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class Order {

	@Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Column(nullable = false)
	private int totalPrice;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OrderStatus status;

	@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<OrderItem> orderItems = new ArrayList<>();

	@Builder
	private Order(Member member, OrderStatus status) {
		this.member = member;
		this.status = status;
		this.totalPrice = 0;
	}

	public static Order create(Member member) {
		return Order.builder()
			.member(member)
			.status(OrderStatus.INIT)
			.build();
	}

	public void addOrderItem(Product product, int quantity) {
		OrderItem orderItem = OrderItem.of(this, product, quantity);
		this.orderItems.add(orderItem);
		this.totalPrice += product.getPrice() * quantity;
	}

}
