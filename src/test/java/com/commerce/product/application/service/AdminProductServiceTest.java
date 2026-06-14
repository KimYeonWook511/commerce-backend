package com.commerce.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.product.application.dto.AdminProductCreateCommand;
import com.commerce.product.application.dto.AdminProductUpdateCommand;
import com.commerce.product.application.dto.AdminProductDeleteResult;
import com.commerce.product.application.dto.AdminProductResult;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.domain.repository.ProductRepository;
import com.commerce.product.domain.exception.ProductErrorCode;
import com.commerce.product.domain.exception.ProductException;

@ExtendWith(MockitoExtension.class)
class AdminProductServiceTest {

	@Mock
	private ProductRepository productRepository;

	@InjectMocks
	private AdminProductService adminProductService;

	@DisplayName("관리자 상품 등록은 상품을 저장하고 결과를 반환한다")
	@Test
	void createProduct_whenValidCommand_saveProduct() {
		// given
		AdminProductCreateCommand command = AdminProductCreateCommand.builder()
			.name("product")
			.price(10000)
			.description("description")
			.imageUrl("https://example.com/product.png")
			.status(ProductStatus.ON_SALE)
			.build();

		given(productRepository.save(any(Product.class)))
			.willAnswer(invocation -> {
				Product savedProduct = invocation.getArgument(0);
				ReflectionTestUtils.setField(savedProduct, "id", 1L);
				return savedProduct;
			});

		// when
		AdminProductResult result = adminProductService.createProduct(command);

		// then
		assertThat(result.getProductId()).isEqualTo(1L);
		assertThat(result.getName()).isEqualTo("product");
		assertThat(result.getPrice()).isEqualTo(10000);
		assertThat(result.getDescription()).isEqualTo("description");
		assertThat(result.getImageUrl()).isEqualTo("https://example.com/product.png");
		assertThat(result.getStatus()).isEqualTo(ProductStatus.ON_SALE);
		then(productRepository).should().save(any(Product.class));
	}

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
		AdminProductResult result = adminProductService.updateProduct(command);

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
		assertThatThrownBy(() -> adminProductService.updateProduct(command))
			.isInstanceOf(ProductException.class)
			.satisfies(exception -> {
				ProductException productException = (ProductException)exception;
				assertThat(productException.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
			});
	}

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
		AdminProductDeleteResult result = adminProductService.deleteProduct(1L);

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
		assertThatThrownBy(() -> adminProductService.deleteProduct(1L))
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
