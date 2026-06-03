package com.commerce.order.domain;

import com.commerce.common.jpa.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

	private OrderItem(Order order, Long productId, int quantity) {
		this.order = order;
		this.productId = productId;
		this.quantity = quantity;
	}

	public static OrderItem of(Order order, Long productId, int quantity) {
		return new OrderItem(order, productId, quantity);
	}

	// 가격도 넣어야 하나? (구매했을때 기준의 가격이 있어야 하지 않나.. 세일,, 등등.. product의 price는 변동하지 않나)
	// 추후 고려하기

}
