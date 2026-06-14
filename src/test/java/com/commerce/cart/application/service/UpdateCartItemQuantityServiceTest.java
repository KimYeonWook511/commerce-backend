package com.commerce.cart.application.service;

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

import com.commerce.cart.application.dto.CartItemSummaryResult;
import com.commerce.cart.domain.CartItem;
import com.commerce.cart.domain.repository.CartItemRepository;
import com.commerce.cart.domain.exception.CartErrorCode;
import com.commerce.cart.domain.exception.CartException;
import com.commerce.cart.presentation.http.request.CartItemUpdateRequest;

@ExtendWith(MockitoExtension.class)
class UpdateCartItemQuantityServiceTest {

	@Mock
	private CartItemRepository cartItemRepository;

	@InjectMocks
	private UpdateCartItemQuantityService processor;

	@DisplayName("기존 항목의 수량을 절대값으로 변경하고 save를 명시 호출한다 (ADR-022)")
	@Test
	void execute_whenExists_changeQuantityAndSave() {
		Long memberId = 1L;
		Long productId = 100L;
		CartItem existing = CartItem.create(memberId, productId, 3);
		CartItemUpdateRequest request = createRequest(7);

		given(cartItemRepository.findByMemberIdAndProductId(memberId, productId))
			.willReturn(Optional.of(existing));

		CartItemSummaryResult result = processor.execute(memberId, productId, request);

		assertThat(result.getProductId()).isEqualTo(productId);
		assertThat(result.getQuantity()).isEqualTo(7);
		assertThat(existing.getQuantity()).isEqualTo(7);
		then(cartItemRepository).should().save(existing);
	}

	@DisplayName("존재하지 않는 항목이면 CART_ITEM_NOT_FOUND 예외를 던지고 save를 호출하지 않는다")
	@Test
	void execute_whenNotExists_throwNotFound() {
		Long memberId = 1L;
		Long productId = 100L;
		CartItemUpdateRequest request = createRequest(5);

		given(cartItemRepository.findByMemberIdAndProductId(memberId, productId))
			.willReturn(Optional.empty());

		assertThatThrownBy(() -> processor.execute(memberId, productId, request))
			.isInstanceOf(CartException.class)
			.satisfies(exception -> {
				CartException cartException = (CartException)exception;
				assertThat(cartException.getErrorCode()).isEqualTo(CartErrorCode.CART_ITEM_NOT_FOUND);
			});
		then(cartItemRepository).should(never()).save(any(CartItem.class));
	}

	@DisplayName("도메인 invariant 위반 수량이면 예외를 던지고 save를 호출하지 않는다")
	@Test
	void execute_whenQuantityExceedsMax_throwException() {
		Long memberId = 1L;
		Long productId = 100L;
		CartItem existing = CartItem.create(memberId, productId, 3);
		CartItemUpdateRequest request = createRequest(100);

		given(cartItemRepository.findByMemberIdAndProductId(memberId, productId))
			.willReturn(Optional.of(existing));

		assertThatThrownBy(() -> processor.execute(memberId, productId, request))
			.isInstanceOf(CartException.class)
			.satisfies(exception -> {
				CartException cartException = (CartException)exception;
				assertThat(cartException.getErrorCode()).isEqualTo(CartErrorCode.CART_ITEM_QUANTITY_EXCEEDED);
			});
		assertThat(existing.getQuantity()).isEqualTo(3);
		then(cartItemRepository).should(never()).save(any(CartItem.class));
	}

	private CartItemUpdateRequest createRequest(int quantity) {
		CartItemUpdateRequest request = new CartItemUpdateRequest();
		ReflectionTestUtils.setField(request, "quantity", quantity);
		return request;
	}
}
