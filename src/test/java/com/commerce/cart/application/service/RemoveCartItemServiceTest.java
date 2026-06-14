package com.commerce.cart.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.never;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.commerce.cart.domain.CartItem;
import com.commerce.cart.domain.repository.CartItemRepository;
import com.commerce.cart.domain.exception.CartErrorCode;
import com.commerce.cart.domain.exception.CartException;

@ExtendWith(MockitoExtension.class)
class RemoveCartItemServiceTest {

	@Mock
	private CartItemRepository cartItemRepository;

	@InjectMocks
	private RemoveCartItemService removeCartItemService;

	@DisplayName("장바구니 항목을 find로 조회한 entity 그대로 delete에 넘긴다")
	@Test
	void remove_whenExists_deletesCartItem() {
		// given
		Long memberId = 1L;
		Long productId = 100L;
		CartItem existing = CartItem.create(memberId, productId, 3);
		given(cartItemRepository.findByMemberIdAndProductId(memberId, productId))
			.willReturn(Optional.of(existing));
		willDoNothing().given(cartItemRepository).delete(existing);

		// when
		removeCartItemService.remove(memberId, productId);

		// then
		then(cartItemRepository).should().delete(existing);
	}

	@DisplayName("존재하지 않는 항목이면 CART_ITEM_NOT_FOUND 예외를 던지고 삭제를 호출하지 않는다 (결정 6-4)")
	@Test
	void remove_whenNotExists_throwNotFound() {
		// given
		Long memberId = 1L;
		Long productId = 999L;
		given(cartItemRepository.findByMemberIdAndProductId(memberId, productId))
			.willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> removeCartItemService.remove(memberId, productId))
			.isInstanceOf(CartException.class)
			.satisfies(exception -> {
				CartException cartException = (CartException)exception;
				assertThat(cartException.getErrorCode()).isEqualTo(CartErrorCode.CART_ITEM_NOT_FOUND);
			});
		then(cartItemRepository).should(never()).delete(any(CartItem.class));
	}
}
