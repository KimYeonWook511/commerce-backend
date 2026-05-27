package com.commerce.cart.application;

import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.commerce.cart.domain.repository.CartItemRepository;

@ExtendWith(MockitoExtension.class)
class RemoveCartItemServiceTest {

	@Mock
	private CartItemRepository cartItemRepository;

	@InjectMocks
	private RemoveCartItemService removeCartItemService;

	@DisplayName("장바구니 항목을 삭제한다")
	@Test
	void remove_whenExists_deletesCartItem() {
		// given
		Long memberId = 1L;
		Long productId = 100L;
		willDoNothing().given(cartItemRepository).deleteByMemberIdAndProductId(memberId, productId);

		// when
		removeCartItemService.remove(memberId, productId);

		// then
		then(cartItemRepository).should().deleteByMemberIdAndProductId(memberId, productId);
	}

	@DisplayName("존재하지 않는 항목이어도 멱등하게 성공 응답한다")
	@Test
	void remove_whenNotExists_idempotentSuccess() {
		// given
		Long memberId = 1L;
		Long productId = 999L;
		willDoNothing().given(cartItemRepository).deleteByMemberIdAndProductId(memberId, productId);

		// when
		removeCartItemService.remove(memberId, productId);

		// then
		then(cartItemRepository).should().deleteByMemberIdAndProductId(memberId, productId);
	}
}
