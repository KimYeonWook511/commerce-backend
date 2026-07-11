package com.commerce.cart.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.cart.application.dto.CartItemResult;
import com.commerce.cart.application.dto.CartResult;
import com.commerce.cart.domain.CartItem;
import com.commerce.cart.domain.repository.CartItemRepository;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.domain.repository.ProductRepository;

@ExtendWith(MockitoExtension.class)
class GetMyCartServiceTest {

	@Mock
	private CartItemRepository cartItemRepository;

	@Mock
	private ProductRepository productRepository;

	@InjectMocks
	private GetMyCartService getMyCartService;

	@DisplayName("비어 있는 장바구니는 빈 응답을 반환한다")
	@Test
	void get_whenCartEmpty_returnEmptyView() {
		// given
		Long memberId = 1L;
		given(cartItemRepository.findAllByMemberIdOrderByCreatedAtDesc(memberId)).willReturn(List.of());

		// when
		CartResult result = getMyCartService.get(memberId);

		// then
		assertThat(result.getItems()).isEmpty();
		assertThat(result.getTotalAmount()).isZero();
	}

	@DisplayName("정상 장바구니는 최신 상품 정보와 합계를 반환한다")
	@Test
	void get_whenCartHasItems_returnViewWithLatestPrice() {
		// given
		Long memberId = 1L;
		CartItem item1 = createCartItem(memberId, 10L, 2);
		CartItem item2 = createCartItem(memberId, 20L, 3);
		Product product1 = createProduct(10L, "product-1", 1000, "https://img/1.png", ProductStatus.ON_SALE, null);
		Product product2 = createProduct(20L, "product-2", 500, "https://img/2.png", ProductStatus.SOLD_OUT, null);

		given(cartItemRepository.findAllByMemberIdOrderByCreatedAtDesc(memberId)).willReturn(List.of(item1, item2));
		given(productRepository.findAllById(List.of(10L, 20L))).willReturn(List.of(product1, product2));

		// when
		CartResult result = getMyCartService.get(memberId);

		// then
		assertThat(result.getItems()).hasSize(2);
		CartItemResult view1 = result.getItems().get(0);
		assertThat(view1.getProductId()).isEqualTo(10L);
		assertThat(view1.getName()).isEqualTo("product-1");
		assertThat(view1.getPrice()).isEqualTo(1000);
		assertThat(view1.getImageUrl()).isEqualTo("https://img/1.png");
		assertThat(view1.getQuantity()).isEqualTo(2);
		assertThat(view1.getLineAmount()).isEqualTo(2000);
		assertThat(view1.isUnavailable()).isFalse();

		CartItemResult view2 = result.getItems().get(1);
		assertThat(view2.getProductId()).isEqualTo(20L);
		assertThat(view2.getPrice()).isEqualTo(500);
		assertThat(view2.getQuantity()).isEqualTo(3);
		assertThat(view2.getLineAmount()).isEqualTo(1500);
		assertThat(view2.isUnavailable()).isFalse();

		assertThat(result.getTotalAmount()).isEqualTo(3500);
	}

	@DisplayName("판매중지 또는 삭제된 상품은 unavailable로 표시하고 합계에서 제외한다")
	@Test
	void get_whenProductUnavailable_excludeFromTotal() {
		// given
		Long memberId = 1L;
		CartItem normalItem = createCartItem(memberId, 10L, 2);
		CartItem stoppedItem = createCartItem(memberId, 20L, 1);
		CartItem deletedItem = createCartItem(memberId, 30L, 4);

		Product normalProduct = createProduct(10L, "product-1", 1000, null, ProductStatus.ON_SALE, null);
		Product stoppedProduct = createProduct(20L, "product-2", 500, null, ProductStatus.STOPPED, null);
		Product deletedProduct = createProduct(30L, "product-3", 700, null, ProductStatus.ON_SALE,
			LocalDateTime.of(2026, 5, 1, 0, 0));

		given(cartItemRepository.findAllByMemberIdOrderByCreatedAtDesc(memberId))
			.willReturn(List.of(normalItem, stoppedItem, deletedItem));
		given(productRepository.findAllById(List.of(10L, 20L, 30L)))
			.willReturn(List.of(normalProduct, stoppedProduct, deletedProduct));

		// when
		CartResult result = getMyCartService.get(memberId);

		// then
		assertThat(result.getItems()).hasSize(3);
		assertThat(result.getItems().get(0).isUnavailable()).isFalse();
		assertThat(result.getItems().get(0).getLineAmount()).isEqualTo(2000);
		assertThat(result.getItems().get(1).isUnavailable()).isTrue();
		assertThat(result.getItems().get(1).getLineAmount()).isEqualTo(500);
		assertThat(result.getItems().get(2).isUnavailable()).isTrue();
		assertThat(result.getItems().get(2).getLineAmount()).isEqualTo(2800);

		assertThat(result.getTotalAmount()).isEqualTo(2000);
	}

	@DisplayName("Product가 누락된 cart 항목은 응답에서 제외한다")
	@Test
	void get_whenProductMissing_excludeItem() {
		// given
		Long memberId = 1L;
		CartItem normalItem = createCartItem(memberId, 10L, 2);
		CartItem orphanItem = createCartItem(memberId, 99L, 1);

		Product normalProduct = createProduct(10L, "product-1", 1000, null, ProductStatus.ON_SALE, null);

		given(cartItemRepository.findAllByMemberIdOrderByCreatedAtDesc(memberId))
			.willReturn(List.of(normalItem, orphanItem));
		given(productRepository.findAllById(List.of(10L, 99L)))
			.willReturn(List.of(normalProduct));

		// when
		CartResult result = getMyCartService.get(memberId);

		// then
		assertThat(result.getItems()).hasSize(1);
		assertThat(result.getItems().get(0).getProductId()).isEqualTo(10L);
		assertThat(result.getTotalAmount()).isEqualTo(2000);
	}

	private CartItem createCartItem(Long memberId, Long productId, int quantity) {
		return CartItem.create(memberId, productId, quantity);
	}

	private Product createProduct(Long id, String name, int price, String imageUrl, ProductStatus status,
		LocalDateTime deletedAt) {
		Product product = Product.create(name, price, null, imageUrl, status);
		ReflectionTestUtils.setField(product, "id", id);
		if (deletedAt != null) {
			ReflectionTestUtils.setField(product, "deletedAt", deletedAt);
		}
		return product;
	}
}
