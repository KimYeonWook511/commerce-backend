package com.commerce.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.product.application.dto.AdminProductDeleteResult;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.domain.repository.ProductRepository;
import com.commerce.product.domain.exception.ProductErrorCode;
import com.commerce.product.domain.exception.ProductException;

@ExtendWith(MockitoExtension.class)
class AdminDeleteProductServiceTest {

	@Mock
	private ProductRepository productRepository;

	@InjectMocks
	private AdminDeleteProductService adminDeleteProductService;

	@DisplayName("관리자 상품 삭제는 상품을 soft delete 한다")
	@Test
	void deleteProduct_whenNotDeletedProductExists_softDeleteProduct() {
		// given
		Product product = createProduct(
			1L,
			"product",
			1000,
			ProductStatus.ON_SALE,
			LocalDateTime.of(2026, 4, 26, 12, 0)
		);
		given(productRepository.findNotDeletedProduct(1L)).willReturn(Optional.of(product));

		// when
		AdminProductDeleteResult result = adminDeleteProductService.deleteProduct(1L);

		// then
		assertThat(result.getProductId()).isEqualTo(1L);
		assertThat(result.isDeleted()).isTrue();
		assertThat(product.getDeletedAt()).isNotNull();
	}

	@DisplayName("삭제되었거나 없는 상품 삭제는 상품 없음 예외를 던진다")
	@Test
	void deleteProduct_whenProductMissing_throwProductNotFound() {
		// given
		given(productRepository.findNotDeletedProduct(1L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> adminDeleteProductService.deleteProduct(1L))
			.isInstanceOf(ProductException.class)
			.satisfies(exception -> {
				ProductException productException = (ProductException)exception;
				assertThat(productException.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
			});
	}

	private Product createProduct(Long id, String name, int price, ProductStatus status, LocalDateTime createdAt) {
		Product product = Product.builder()
			.name(name)
			.price(price)
			.status(status)
			.build();
		ReflectionTestUtils.setField(product, "id", id);
		ReflectionTestUtils.setField(product, "createdAt", createdAt);
		return product;
	}
}
