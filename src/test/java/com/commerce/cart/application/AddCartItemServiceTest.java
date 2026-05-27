package com.commerce.cart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

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
import com.commerce.cart.presentation.request.CartItemAddRequest;

@ExtendWith(MockitoExtension.class)
class AddCartItemServiceTest {

	@Mock
	private CartItemRepository cartItemRepository;

	@InjectMocks
	private AddCartItemService addCartItemService;

	@DisplayName("기존 항목이 없으면 새로 저장한다")
	@Test
	void add_whenNotExists_saveNewCartItem() {
		// given
		Long memberId = 1L;
		Long productId = 100L;
		CartItemAddRequest request = createRequest(productId, 3);

		given(cartItemRepository.findByMemberIdAndProductId(memberId, productId))
			.willReturn(Optional.empty());
		given(cartItemRepository.save(any(CartItem.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		// when
		CartItemAddedView result = addCartItemService.add(memberId, request);

		// then
		assertThat(result.getProductId()).isEqualTo(productId);
		assertThat(result.getQuantity()).isEqualTo(3);
		then(cartItemRepository).should().save(any(CartItem.class));
	}

	@DisplayName("기존 항목이 있으면 수량을 합산한다")
	@Test
	void add_whenExists_increaseQuantity() {
		// given
		Long memberId = 1L;
		Long productId = 100L;
		CartItem existing = CartItem.create(memberId, productId, 5);
		CartItemAddRequest request = createRequest(productId, 4);

		given(cartItemRepository.findByMemberIdAndProductId(memberId, productId))
			.willReturn(Optional.of(existing));

		// when
		CartItemAddedView result = addCartItemService.add(memberId, request);

		// then
		assertThat(result.getProductId()).isEqualTo(productId);
		assertThat(result.getQuantity()).isEqualTo(9);
		assertThat(existing.getQuantity()).isEqualTo(9);
		then(cartItemRepository).should(never()).save(any(CartItem.class));
	}

	@DisplayName("합산 결과가 최대 수량을 초과하면 예외를 던진다")
	@Test
	void add_whenSumExceedsMaxQuantity_throwException() {
		// given
		Long memberId = 1L;
		Long productId = 100L;
		CartItem existing = CartItem.create(memberId, productId, 95);
		CartItemAddRequest request = createRequest(productId, 10);

		given(cartItemRepository.findByMemberIdAndProductId(memberId, productId))
			.willReturn(Optional.of(existing));

		// when & then
		assertThatThrownBy(() -> addCartItemService.add(memberId, request))
			.isInstanceOf(CartException.class)
			.satisfies(exception -> {
				CartException cartException = (CartException)exception;
				assertThat(cartException.getErrorCode()).isEqualTo(CartErrorCode.CART_ITEM_QUANTITY_EXCEEDED);
			});
		assertThat(existing.getQuantity()).isEqualTo(95);
		then(cartItemRepository).should(never()).save(any(CartItem.class));
	}

	private CartItemAddRequest createRequest(Long productId, int quantity) {
		CartItemAddRequest request = new CartItemAddRequest();
		ReflectionTestUtils.setField(request, "productId", productId);
		ReflectionTestUtils.setField(request, "quantity", quantity);
		return request;
	}
}
