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
import com.commerce.cart.presentation.http.request.CartItemAddRequest;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.domain.repository.ProductRepository;

@ExtendWith(MockitoExtension.class)
class AddCartItemServiceTest {

	@Mock
	private CartItemRepository cartItemRepository;

	@Mock
	private ProductRepository productRepository;

	@InjectMocks
	private AddCartItemService processor;

	@DisplayName("기존 항목이 없으면 새로 저장한다")
	@Test
	void execute_whenNotExists_saveNewCartItem() {
		Long memberId = 1L;
		Long productId = 100L;
		CartItemAddRequest request = createRequest(productId, 3);
		stubProduct(productId, ProductStatus.ON_SALE);

		given(cartItemRepository.findByMemberIdAndProductId(memberId, productId))
			.willReturn(Optional.empty());
		given(cartItemRepository.save(any(CartItem.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		CartItemSummaryResult result = processor.execute(memberId, request);

		assertThat(result.getProductId()).isEqualTo(productId);
		assertThat(result.getQuantity()).isEqualTo(3);
		then(cartItemRepository).should().save(any(CartItem.class));
	}

	@DisplayName("기존 항목이 있으면 수량을 합산하고 save를 명시 호출한다 (ADR-022)")
	@Test
	void execute_whenExists_increaseQuantityAndSave() {
		Long memberId = 1L;
		Long productId = 100L;
		CartItem existing = CartItem.create(memberId, productId, 5);
		CartItemAddRequest request = createRequest(productId, 4);
		stubProduct(productId, ProductStatus.ON_SALE);

		given(cartItemRepository.findByMemberIdAndProductId(memberId, productId))
			.willReturn(Optional.of(existing));
		given(cartItemRepository.save(existing)).willReturn(existing);

		CartItemSummaryResult result = processor.execute(memberId, request);

		assertThat(result.getProductId()).isEqualTo(productId);
		assertThat(result.getQuantity()).isEqualTo(9);
		assertThat(existing.getQuantity()).isEqualTo(9);
		then(cartItemRepository).should().save(existing);
	}

	@DisplayName("합산 결과가 최대 수량을 초과하면 예외를 던지고 save를 호출하지 않는다")
	@Test
	void execute_whenSumExceedsMaxQuantity_throwException() {
		Long memberId = 1L;
		Long productId = 100L;
		CartItem existing = CartItem.create(memberId, productId, 95);
		CartItemAddRequest request = createRequest(productId, 10);
		stubProduct(productId, ProductStatus.ON_SALE);

		given(cartItemRepository.findByMemberIdAndProductId(memberId, productId))
			.willReturn(Optional.of(existing));

		assertThatThrownBy(() -> processor.execute(memberId, request))
			.isInstanceOf(CartException.class)
			.satisfies(exception -> {
				CartException cartException = (CartException)exception;
				assertThat(cartException.getErrorCode()).isEqualTo(CartErrorCode.CART_ITEM_QUANTITY_EXCEEDED);
			});
		assertThat(existing.getQuantity()).isEqualTo(95);
		then(cartItemRepository).should(never()).save(any(CartItem.class));
	}

	@DisplayName("Product가 존재하지 않으면 CART_ITEM_PRODUCT_NOT_FOUND를 던지고 cart row를 만들지 않는다 (결정 6-5)")
	@Test
	void execute_whenProductMissing_throwNotFound() {
		Long memberId = 1L;
		Long productId = 999L;
		CartItemAddRequest request = createRequest(productId, 3);

		given(productRepository.findById(productId)).willReturn(Optional.empty());

		assertThatThrownBy(() -> processor.execute(memberId, request))
			.isInstanceOf(CartException.class)
			.satisfies(exception -> {
				CartException cartException = (CartException)exception;
				assertThat(cartException.getErrorCode()).isEqualTo(CartErrorCode.CART_ITEM_PRODUCT_NOT_FOUND);
			});
		then(cartItemRepository).should(never()).findByMemberIdAndProductId(memberId, productId);
		then(cartItemRepository).should(never()).save(any(CartItem.class));
	}

	@DisplayName("Product가 soft-deleted면 CART_ITEM_PRODUCT_NOT_FOUND를 던진다 (결정 6-5)")
	@Test
	void execute_whenProductSoftDeleted_throwNotFound() {
		Long memberId = 1L;
		Long productId = 100L;
		CartItemAddRequest request = createRequest(productId, 3);
		Product product = createProduct(ProductStatus.ON_SALE);
		product.softDelete();

		given(productRepository.findById(productId)).willReturn(Optional.of(product));

		assertThatThrownBy(() -> processor.execute(memberId, request))
			.isInstanceOf(CartException.class)
			.satisfies(exception -> {
				CartException cartException = (CartException)exception;
				assertThat(cartException.getErrorCode()).isEqualTo(CartErrorCode.CART_ITEM_PRODUCT_NOT_FOUND);
			});
		then(cartItemRepository).should(never()).findByMemberIdAndProductId(memberId, productId);
		then(cartItemRepository).should(never()).save(any(CartItem.class));
	}

	@DisplayName("Product가 STOPPED면 CART_ITEM_PRODUCT_UNAVAILABLE를 던진다 (결정 6-5)")
	@Test
	void execute_whenProductStopped_throwUnavailable() {
		Long memberId = 1L;
		Long productId = 100L;
		CartItemAddRequest request = createRequest(productId, 3);

		given(productRepository.findById(productId))
			.willReturn(Optional.of(createProduct(ProductStatus.STOPPED)));

		assertThatThrownBy(() -> processor.execute(memberId, request))
			.isInstanceOf(CartException.class)
			.satisfies(exception -> {
				CartException cartException = (CartException)exception;
				assertThat(cartException.getErrorCode()).isEqualTo(CartErrorCode.CART_ITEM_PRODUCT_UNAVAILABLE);
			});
		then(cartItemRepository).should(never()).findByMemberIdAndProductId(memberId, productId);
		then(cartItemRepository).should(never()).save(any(CartItem.class));
	}

	private void stubProduct(Long productId, ProductStatus status) {
		given(productRepository.findById(productId))
			.willReturn(Optional.of(createProduct(status)));
	}

	private Product createProduct(ProductStatus status) {
		return Product.builder()
			.name("test-product")
			.price(1000)
			.status(status)
			.build();
	}

	private CartItemAddRequest createRequest(Long productId, int quantity) {
		CartItemAddRequest request = new CartItemAddRequest();
		ReflectionTestUtils.setField(request, "productId", productId);
		ReflectionTestUtils.setField(request, "quantity", quantity);
		return request;
	}
}
