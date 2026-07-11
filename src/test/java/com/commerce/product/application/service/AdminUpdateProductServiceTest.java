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

import com.commerce.product.application.dto.AdminProductResult;
import com.commerce.product.application.dto.AdminProductUpdateCommand;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.domain.repository.ProductRepository;
import com.commerce.product.domain.exception.ProductErrorCode;
import com.commerce.product.domain.exception.ProductException;

@ExtendWith(MockitoExtension.class)
class AdminUpdateProductServiceTest {

	@Mock
	private ProductRepository productRepository;

	@InjectMocks
	private AdminUpdateProductService adminUpdateProductService;

	@DisplayName("관리자 상품 수정은 삭제되지 않은 상품만 수정한다")
	@Test
	void updateProduct_whenNotDeletedProductExists_updateProduct() {
		// given
		Product product = createProduct(
			1L,
			"product",
			1000,
			ProductStatus.ON_SALE,
			LocalDateTime.of(2026, 4, 26, 12, 0)
		);
		AdminProductUpdateCommand command = AdminProductUpdateCommand.builder()
			.productId(1L)
			.name("updated-product")
			.price(12000)
			.description("updated description")
			.imageUrl("https://example.com/updated.png")
			.status(ProductStatus.SOLD_OUT)
			.build();

		given(productRepository.findNotDeletedProduct(1L)).willReturn(Optional.of(product));

		// when
		AdminProductResult result = adminUpdateProductService.updateProduct(command);

		// then
		assertThat(result.getProductId()).isEqualTo(1L);
		assertThat(result.getName()).isEqualTo("updated-product");
		assertThat(result.getPrice()).isEqualTo(12000);
		assertThat(result.getDescription()).isEqualTo("updated description");
		assertThat(result.getImageUrl()).isEqualTo("https://example.com/updated.png");
		assertThat(result.getStatus()).isEqualTo(ProductStatus.SOLD_OUT);
		assertThat(product.getName()).isEqualTo("updated-product");
		assertThat(product.getStatus()).isEqualTo(ProductStatus.SOLD_OUT);
	}

	@DisplayName("삭제되었거나 없는 상품 수정은 상품 없음 예외를 던진다")
	@Test
	void updateProduct_whenProductMissing_throwProductNotFound() {
		// given
		AdminProductUpdateCommand command = AdminProductUpdateCommand.builder()
			.productId(1L)
			.name("updated-product")
			.price(12000)
			.status(ProductStatus.SOLD_OUT)
			.build();

		given(productRepository.findNotDeletedProduct(1L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> adminUpdateProductService.updateProduct(command))
			.isInstanceOf(ProductException.class)
			.satisfies(exception -> {
				ProductException productException = (ProductException)exception;
				assertThat(productException.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
			});
	}

	private Product createProduct(Long id, String name, int price, ProductStatus status, LocalDateTime createdAt) {
		Product product = Product.create(name, price, null, null, status);
		ReflectionTestUtils.setField(product, "id", id);
		ReflectionTestUtils.setField(product, "createdAt", createdAt);
		return product;
	}
}
