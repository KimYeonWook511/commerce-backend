package com.commerce.cart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.cart.application.result.CartItemAddedView;
import com.commerce.cart.domain.CartItem;
import com.commerce.cart.domain.repository.CartItemRepository;
import com.commerce.cart.exception.CartErrorCode;
import com.commerce.cart.exception.CartException;
import com.commerce.cart.presentation.request.CartItemUpdateRequest;

@ExtendWith(MockitoExtension.class)
class UpdateCartItemQuantityServiceTest {

	@Mock
	private CartItemRepository cartItemRepository;

	@InjectMocks
	private UpdateCartItemQuantityService updateCartItemQuantityService;

	@DisplayName("기존 항목의 수량을 절대값으로 변경한다")
	@Test
	void update_whenExists_changeQuantity() {
		// given
		Long memberId = 1L;
		Long productId = 100L;
		CartItem existing = CartItem.create(memberId, productId, 3);
		CartItemUpdateRequest request = createRequest(7);

		given(cartItemRepository.findByMemberIdAndProductId(memberId, productId))
			.willReturn(Optional.of(existing));

		// when
		CartItemAddedView result = updateCartItemQuantityService.update(memberId, productId, request);

		// then
		assertThat(result.getProductId()).isEqualTo(productId);
		assertThat(result.getQuantity()).isEqualTo(7);
		assertThat(existing.getQuantity()).isEqualTo(7);
	}

	@DisplayName("존재하지 않는 항목이면 CART_ITEM_NOT_FOUND 예외를 던진다")
	@Test
	void update_whenNotExists_throwNotFound() {
		// given
		Long memberId = 1L;
		Long productId = 100L;
		CartItemUpdateRequest request = createRequest(5);

		given(cartItemRepository.findByMemberIdAndProductId(memberId, productId))
			.willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> updateCartItemQuantityService.update(memberId, productId, request))
			.isInstanceOf(CartException.class)
			.satisfies(exception -> {
				CartException cartException = (CartException)exception;
				assertThat(cartException.getErrorCode()).isEqualTo(CartErrorCode.CART_ITEM_NOT_FOUND);
			});
	}

	@DisplayName("도메인 invariant 위반 수량이면 예외를 던진다")
	@Test
	void update_whenQuantityExceedsMax_throwException() {
		// given
		Long memberId = 1L;
		Long productId = 100L;
		CartItem existing = CartItem.create(memberId, productId, 3);
		CartItemUpdateRequest request = createRequest(100);

		given(cartItemRepository.findByMemberIdAndProductId(memberId, productId))
			.willReturn(Optional.of(existing));

		// when & then
		assertThatThrownBy(() -> updateCartItemQuantityService.update(memberId, productId, request))
			.isInstanceOf(CartException.class)
			.satisfies(exception -> {
				CartException cartException = (CartException)exception;
				assertThat(cartException.getErrorCode()).isEqualTo(CartErrorCode.CART_ITEM_QUANTITY_EXCEEDED);
			});
		assertThat(existing.getQuantity()).isEqualTo(3);
	}

	private CartItemUpdateRequest createRequest(int quantity) {
		CartItemUpdateRequest request = new CartItemUpdateRequest();
		ReflectionTestUtils.setField(request, "quantity", quantity);
		return request;
	}
}
