package com.commerce.cart.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.commerce.cart.domain.exception.CartErrorCode;
import com.commerce.cart.domain.exception.CartException;

class CartItemTest {

	private static final Long MEMBER_ID = 1L;
	private static final Long PRODUCT_ID = 100L;

	@DisplayName("정상 수량으로 생성하면 값이 보존된다")
	@Test
	void create_whenValidQuantity_returnsCartItem() {
		// when
		CartItem cartItem = CartItem.create(MEMBER_ID, PRODUCT_ID, 3);

		// then
		assertThat(cartItem.getMemberId()).isEqualTo(MEMBER_ID);
		assertThat(cartItem.getProductId()).isEqualTo(PRODUCT_ID);
		assertThat(cartItem.getQuantity()).isEqualTo(3);
	}

	@DisplayName("MIN 경계 수량(1)으로 생성하면 성공한다")
	@Test
	void create_whenQuantityIsMinBoundary_returnsCartItem() {
		// when
		CartItem cartItem = CartItem.create(MEMBER_ID, PRODUCT_ID, CartItem.MIN_QUANTITY);

		// then
		assertThat(cartItem.getQuantity()).isEqualTo(CartItem.MIN_QUANTITY);
	}

	@DisplayName("MAX 경계 수량(99)으로 생성하면 성공한다")
	@Test
	void create_whenQuantityIsMaxBoundary_returnsCartItem() {
		// when
		CartItem cartItem = CartItem.create(MEMBER_ID, PRODUCT_ID, CartItem.MAX_QUANTITY);

		// then
		assertThat(cartItem.getQuantity()).isEqualTo(CartItem.MAX_QUANTITY);
	}

	@DisplayName("memberId가 null이면 NullPointerException을 던진다")
	@Test
	void create_whenMemberIdNull_throwsNullPointerException() {
		// when & then
		assertThatThrownBy(() -> CartItem.create(null, PRODUCT_ID, 1))
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("memberId");
	}

	@DisplayName("productId가 null이면 NullPointerException을 던진다")
	@Test
	void create_whenProductIdNull_throwsNullPointerException() {
		// when & then
		assertThatThrownBy(() -> CartItem.create(MEMBER_ID, null, 1))
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("productId");
	}

	@DisplayName("수량이 1 미만이면 INVALID_CART_ITEM_QUANTITY 예외를 던진다")
	@Test
	void create_whenQuantityBelowMin_throwsInvalidQuantity() {
		// when & then
		assertThatThrownBy(() -> CartItem.create(MEMBER_ID, PRODUCT_ID, 0))
			.isInstanceOf(CartException.class)
			.hasFieldOrPropertyWithValue("errorCode", CartErrorCode.INVALID_CART_ITEM_QUANTITY);
	}

	@DisplayName("수량이 99 초과면 CART_ITEM_QUANTITY_EXCEEDED 예외를 던진다")
	@Test
	void create_whenQuantityExceedsMax_throwsQuantityExceeded() {
		// when & then
		assertThatThrownBy(() -> CartItem.create(MEMBER_ID, PRODUCT_ID, 100))
			.isInstanceOf(CartException.class)
			.hasFieldOrPropertyWithValue("errorCode", CartErrorCode.CART_ITEM_QUANTITY_EXCEEDED);
	}

	@DisplayName("changeQuantity는 절대값으로 수량을 변경한다")
	@Test
	void changeQuantity_whenValid_replacesQuantity() {
		// given
		CartItem cartItem = CartItem.create(MEMBER_ID, PRODUCT_ID, 3);

		// when
		cartItem.changeQuantity(10);

		// then
		assertThat(cartItem.getQuantity()).isEqualTo(10);
	}

	@DisplayName("changeQuantity는 MIN/MAX 경계값을 허용한다")
	@Test
	void changeQuantity_whenBoundary_replacesQuantity() {
		// given
		CartItem cartItem = CartItem.create(MEMBER_ID, PRODUCT_ID, 5);

		// when
		cartItem.changeQuantity(CartItem.MIN_QUANTITY);

		// then
		assertThat(cartItem.getQuantity()).isEqualTo(CartItem.MIN_QUANTITY);

		// when
		cartItem.changeQuantity(CartItem.MAX_QUANTITY);

		// then
		assertThat(cartItem.getQuantity()).isEqualTo(CartItem.MAX_QUANTITY);
	}

	@DisplayName("changeQuantity로 1 미만 변경 시 예외를 던진다")
	@Test
	void changeQuantity_whenBelowMin_throwsInvalidQuantity() {
		// given
		CartItem cartItem = CartItem.create(MEMBER_ID, PRODUCT_ID, 3);

		// when & then
		assertThatThrownBy(() -> cartItem.changeQuantity(0))
			.isInstanceOf(CartException.class)
			.hasFieldOrPropertyWithValue("errorCode", CartErrorCode.INVALID_CART_ITEM_QUANTITY);
	}

	@DisplayName("changeQuantity로 99 초과 변경 시 예외를 던진다")
	@Test
	void changeQuantity_whenExceedsMax_throwsQuantityExceeded() {
		// given
		CartItem cartItem = CartItem.create(MEMBER_ID, PRODUCT_ID, 3);

		// when & then
		assertThatThrownBy(() -> cartItem.changeQuantity(100))
			.isInstanceOf(CartException.class)
			.hasFieldOrPropertyWithValue("errorCode", CartErrorCode.CART_ITEM_QUANTITY_EXCEEDED);
	}

	@DisplayName("increaseQuantity는 현재 수량과 delta를 더한 값을 적용한다")
	@Test
	void increaseQuantity_whenSumValid_addsDelta() {
		// given
		CartItem cartItem = CartItem.create(MEMBER_ID, PRODUCT_ID, 3);

		// when
		cartItem.increaseQuantity(2);

		// then
		assertThat(cartItem.getQuantity()).isEqualTo(5);
	}

	@DisplayName("increaseQuantity는 합산이 MAX 경계값이면 허용한다")
	@Test
	void increaseQuantity_whenSumEqualsMax_addsDelta() {
		// given
		CartItem cartItem = CartItem.create(MEMBER_ID, PRODUCT_ID, 90);

		// when
		cartItem.increaseQuantity(9);

		// then
		assertThat(cartItem.getQuantity()).isEqualTo(CartItem.MAX_QUANTITY);
	}

	@DisplayName("increaseQuantity로 합산이 99 초과면 CART_ITEM_QUANTITY_EXCEEDED 예외를 던진다")
	@Test
	void increaseQuantity_whenSumExceedsMax_throwsQuantityExceeded() {
		// given
		CartItem cartItem = CartItem.create(MEMBER_ID, PRODUCT_ID, 90);

		// when & then
		assertThatThrownBy(() -> cartItem.increaseQuantity(10))
			.isInstanceOf(CartException.class)
			.hasFieldOrPropertyWithValue("errorCode", CartErrorCode.CART_ITEM_QUANTITY_EXCEEDED);
		assertThat(cartItem.getQuantity()).isEqualTo(90);
	}
}
