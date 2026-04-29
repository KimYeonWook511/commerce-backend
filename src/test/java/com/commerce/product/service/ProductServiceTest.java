package com.commerce.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

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
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.exception.ProductErrorCode;
import com.commerce.product.exception.ProductException;
import com.commerce.product.repository.ProductRepository;
import com.commerce.product.service.command.AdminProductCreateCommand;
import com.commerce.product.service.command.AdminProductUpdateCommand;
import com.commerce.product.service.result.AdminProductDeleteResult;
import com.commerce.product.service.result.AdminProductResult;
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
		AdminProductResult result = productService.createProduct(command);

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
	void updateProduct_whenActiveProductExists_updateProduct() {
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

		given(productRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(product));

		// when
		AdminProductResult result = productService.updateProduct(command);

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

		given(productRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> productService.updateProduct(command))
			.isInstanceOf(ProductException.class)
			.satisfies(exception -> {
				ProductException productException = (ProductException)exception;
				assertThat(productException.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
			});
	}

	@DisplayName("관리자 상품 삭제는 상품을 soft delete 한다")
	@Test
	void deleteProduct_whenActiveProductExists_softDeleteProduct() {
		// given
		Product product = createProduct(
			1L,
			"product",
			1000,
			ProductStatus.ON_SALE,
			LocalDateTime.of(2026, 4, 26, 12, 0)
		);
		given(productRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(product));

		// when
		AdminProductDeleteResult result = productService.deleteProduct(1L);

		// then
		assertThat(result.getProductId()).isEqualTo(1L);
		assertThat(result.isDeleted()).isTrue();
		assertThat(product.getDeletedAt()).isNotNull();
	}

	@DisplayName("삭제되었거나 없는 상품 삭제는 상품 없음 예외를 던진다")
	@Test
	void deleteProduct_whenProductMissing_throwProductNotFound() {
		// given
		given(productRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> productService.deleteProduct(1L))
			.isInstanceOf(ProductException.class)
			.satisfies(exception -> {
				ProductException productException = (ProductException)exception;
				assertThat(productException.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
			});
	}

	@DisplayName("상품 목록은 최신 등록순으로 반환한다")
	@Test
	void getProducts_whenProductsExist_returnProductsOrderedByCreatedAtDesc() {
		// given
		Product latestProduct = createProduct(
			2L,
			"latest-product",
			3000,
			ProductStatus.ON_SALE,
			LocalDateTime.of(2026, 4, 26, 12, 0)
		);
		Product oldProduct = createProduct(
			1L,
			"old-product",
			1000,
			ProductStatus.SOLD_OUT,
			LocalDateTime.of(2026, 4, 25, 12, 0)
		);

		given(productRepository.findAllByDeletedAtIsNullAndStatusInOrderByCreatedAtDesc(ProductStatus.publicStatuses()))
			.willReturn(List.of(latestProduct, oldProduct));

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
		Product product = createProduct(
			1L,
			"product",
			1000,
			ProductStatus.ON_SALE,
			LocalDateTime.of(2026, 4, 26, 12, 0)
		);
		Stock stock = createStock(product, 7);

		given(productRepository.findByIdAndDeletedAtIsNullAndStatusIn(1L, ProductStatus.publicStatuses()))
			.willReturn(Optional.of(product));
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
		Product product = createProduct(
			1L,
			"product",
			1000,
			ProductStatus.SOLD_OUT,
			LocalDateTime.of(2026, 4, 26, 12, 0)
		);

		given(productRepository.findByIdAndDeletedAtIsNullAndStatusIn(1L, ProductStatus.publicStatuses()))
			.willReturn(Optional.of(product));
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
		given(productRepository.findByIdAndDeletedAtIsNullAndStatusIn(1L, ProductStatus.publicStatuses()))
			.willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> productService.getProduct(1L))
			.isInstanceOf(ProductException.class)
			.satisfies(exception -> {
				ProductException productException = (ProductException)exception;
				assertThat(productException.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
			});
	}

	@DisplayName("판매중지 상품 상세 조회는 상품 없음 예외를 던진다")
	@Test
	void getProduct_whenProductStopped_throwProductNotFound() {
		// given
		given(productRepository.findByIdAndDeletedAtIsNullAndStatusIn(1L, ProductStatus.publicStatuses()))
			.willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> productService.getProduct(1L))
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

	private Stock createStock(Product product, int quantity) {
		return Stock.builder()
			.product(product)
			.quantity(quantity)
			.build();
	}
}
