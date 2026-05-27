package com.commerce.cart.domain;

import com.commerce.cart.exception.CartErrorCode;
import com.commerce.cart.exception.CartException;
import com.commerce.common.jpa.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "tbl_cart_item", uniqueConstraints = {
	@UniqueConstraint(name = "uk_cart_item_member_product", columnNames = {"member_id", "product_id"})
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class CartItem extends BaseTimeEntity {

	public static final int MIN_QUANTITY = 1;
	public static final int MAX_QUANTITY = 99;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Version
	private Long version;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(name = "product_id", nullable = false)
	private Long productId;

	@Column(nullable = false)
	private int quantity;

	@Builder
	private CartItem(Long memberId, Long productId, int quantity) {
		this.memberId = memberId;
		this.productId = productId;
		this.quantity = quantity;
	}

	public static CartItem create(Long memberId, Long productId, int quantity) {
		validateQuantity(quantity);
		return CartItem.builder()
			.memberId(memberId)
			.productId(productId)
			.quantity(quantity)
			.build();
	}

	public void changeQuantity(int quantity) {
		validateQuantity(quantity);
		this.quantity = quantity;
	}

	public void increaseQuantity(int delta) {
		int next = this.quantity + delta;
		validateQuantity(next);
		this.quantity = next;
	}

	private static void validateQuantity(int quantity) {
		if (quantity < MIN_QUANTITY) {
			throw new CartException(CartErrorCode.INVALID_CART_ITEM_QUANTITY);
		}
		if (quantity > MAX_QUANTITY) {
			throw new CartException(CartErrorCode.CART_ITEM_QUANTITY_EXCEEDED);
		}
	}
}
