package com.commerce.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.product.application.dto.AdminProductCreateCommand;
import com.commerce.product.application.dto.AdminProductResult;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.domain.repository.ProductRepository;

@ExtendWith(MockitoExtension.class)
class AdminCreateProductServiceTest {

	@Mock
	private ProductRepository productRepository;

	@InjectMocks
	private AdminCreateProductService adminCreateProductService;

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
		AdminProductResult result = adminCreateProductService.createProduct(command);

		// then
		assertThat(result.getProductId()).isEqualTo(1L);
		assertThat(result.getName()).isEqualTo("product");
		assertThat(result.getPrice()).isEqualTo(10000);
		assertThat(result.getDescription()).isEqualTo("description");
		assertThat(result.getImageUrl()).isEqualTo("https://example.com/product.png");
		assertThat(result.getStatus()).isEqualTo(ProductStatus.ON_SALE);
		then(productRepository).should().save(any(Product.class));
	}
}
