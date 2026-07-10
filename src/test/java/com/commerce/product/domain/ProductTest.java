package com.commerce.product.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductTest {

	@DisplayName("판매중 또는 품절 상품은 공개 조회 대상이다")
	@Test
	void isPubliclyVisible_whenStatusIsPublic_returnTrue() {
		// given
		Product onSaleProduct = createProduct(ProductStatus.ON_SALE);
		Product soldOutProduct = createProduct(ProductStatus.SOLD_OUT);

		// when & then
		assertThat(onSaleProduct.isPubliclyVisible()).isTrue();
		assertThat(soldOutProduct.isPubliclyVisible()).isTrue();
	}

	@DisplayName("판매중지 상품은 공개 조회 대상이 아니다")
	@Test
	void isPubliclyVisible_whenStatusIsStopped_returnFalse() {
		// given
		Product product = createProduct(ProductStatus.STOPPED);

		// when & then
		assertThat(product.isPubliclyVisible()).isFalse();
	}

	@DisplayName("soft delete 된 상품은 공개 조회 대상이 아니다")
	@Test
	void isPubliclyVisible_whenProductSoftDeleted_returnFalse() {
		// given
		Product product = createProduct(ProductStatus.ON_SALE);

		// when
		product.softDelete();

		// then
		assertThat(product.getDeletedAt()).isNotNull();
		assertThat(product.isPubliclyVisible()).isFalse();
	}

	@DisplayName("상품 가격은 0보다 커야 한다")
	@Test
	void createProduct_whenPriceIsNotPositive_throwIllegalArgumentException() {
		// when & then
		assertThatThrownBy(() -> Product.create("product", 0, null, null, ProductStatus.ON_SALE))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Product price must be greater than 0.");
	}

	@DisplayName("상품 생성 시 상태는 필수다")
	@Test
	void createProduct_whenStatusIsNull_throwIllegalArgumentException() {
		// when & then
		assertThatThrownBy(() -> Product.create("product", 1000, null, null, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Product status is required.");
	}

	@DisplayName("상품 수정 시 필드를 갱신한다")
	@Test
	void update_whenValidValues_updateProduct() {
		// given
		Product product = createProduct(ProductStatus.ON_SALE);

		// when
		product.update(
			"updated-product",
			12000,
			"updated description",
			"https://example.com/updated.png",
			ProductStatus.SOLD_OUT
		);

		// then
		assertThat(product.getName()).isEqualTo("updated-product");
		assertThat(product.getPrice()).isEqualTo(12000);
		assertThat(product.getDescription()).isEqualTo("updated description");
		assertThat(product.getImageUrl()).isEqualTo("https://example.com/updated.png");
		assertThat(product.getStatus()).isEqualTo(ProductStatus.SOLD_OUT);
	}

	private Product createProduct(ProductStatus status) {
		return Product.create("product", 1000, "description", "https://example.com/product.png", status);
	}
}
