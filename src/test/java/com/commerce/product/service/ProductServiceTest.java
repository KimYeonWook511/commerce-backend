package com.commerce.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.product.domain.Product;
import com.commerce.product.exception.ProductErrorCode;
import com.commerce.product.exception.ProductException;
import com.commerce.product.repository.ProductRepository;
import com.commerce.product.service.result.ProductDetailResult;
import com.commerce.product.service.result.ProductSummaryResult;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.repository.StockRepository;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

	@Mock
	private ProductRepository productRepository;

	@Mock
	private StockRepository stockRepository;

	@InjectMocks
	private ProductService productService;

	@DisplayName("상품 목록은 최신 등록순으로 반환한다")
	@Test
	void getProducts_whenProductsExist_returnProductsOrderedByCreatedAtDesc() {
		// given
		Product latestProduct = createProduct(2L, "latest-product", 3000, LocalDateTime.of(2026, 4, 26, 12, 0));
		Product oldProduct = createProduct(1L, "old-product", 1000, LocalDateTime.of(2026, 4, 25, 12, 0));

		given(productRepository.findAllByOrderByCreatedAtDesc()).willReturn(List.of(latestProduct, oldProduct));

		// when
		List<ProductSummaryResult> results = productService.getProducts();

		// then
		assertThat(results).hasSize(2);
		assertThat(results.get(0).getProductId()).isEqualTo(2L);
		assertThat(results.get(0).getName()).isEqualTo("latest-product");
		assertThat(results.get(0).getPrice()).isEqualTo(3000);
		assertThat(results.get(1).getProductId()).isEqualTo(1L);
		assertThat(results.get(1).getName()).isEqualTo("old-product");
		assertThat(results.get(1).getPrice()).isEqualTo(1000);
	}

	@DisplayName("상품 상세 조회는 재고 수량을 함께 반환한다")
	@Test
	void getProduct_whenStockExists_returnProductDetail() {
		// given
		Product product = createProduct(1L, "product", 1000, LocalDateTime.of(2026, 4, 26, 12, 0));
		Stock stock = createStock(product, 7);

		given(productRepository.findById(1L)).willReturn(Optional.of(product));
		given(stockRepository.findByProductId(1L)).willReturn(Optional.of(stock));

		// when
		ProductDetailResult result = productService.getProduct(1L);

		// then
		assertThat(result.getProductId()).isEqualTo(1L);
		assertThat(result.getName()).isEqualTo("product");
		assertThat(result.getPrice()).isEqualTo(1000);
		assertThat(result.getStockQuantity()).isEqualTo(7);
	}

	@DisplayName("재고 레코드가 없으면 재고 수량은 0으로 반환한다")
	@Test
	void getProduct_whenStockMissing_returnZeroStockQuantity() {
		// given
		Product product = createProduct(1L, "product", 1000, LocalDateTime.of(2026, 4, 26, 12, 0));

		given(productRepository.findById(1L)).willReturn(Optional.of(product));
		given(stockRepository.findByProductId(1L)).willReturn(Optional.empty());

		// when
		ProductDetailResult result = productService.getProduct(1L);

		// then
		assertThat(result.getStockQuantity()).isEqualTo(0);
	}

	@DisplayName("없는 상품 상세 조회는 상품 없음 예외를 던진다")
	@Test
	void getProduct_whenProductMissing_throwProductNotFound() {
		// given
		given(productRepository.findById(1L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> productService.getProduct(1L))
			.isInstanceOf(ProductException.class)
			.satisfies(exception -> {
				ProductException productException = (ProductException)exception;
				assertThat(productException.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
			});
	}

	private Product createProduct(Long id, String name, int price, LocalDateTime createdAt) {
		Product product = Product.builder()
			.name(name)
			.price(price)
			.build();
		ReflectionTestUtils.setField(product, "id", id);
		ReflectionTestUtils.setField(product, "createdAt", createdAt);
		return product;
	}

	private Stock createStock(Product product, int quantity) {
		return Stock.builder()
			.product(product)
			.quantity(quantity)
			.build();
	}
}
