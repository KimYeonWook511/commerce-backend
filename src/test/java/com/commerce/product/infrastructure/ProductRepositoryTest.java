package com.commerce.product.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.commerce.common.jpa.JpaConfig;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;

import jakarta.persistence.EntityManager;

@DataJpaTest
@Import(JpaConfig.class)
@ActiveProfiles("test")
class ProductRepositoryTest {

	@Autowired
	private JpaProductRepository productRepository;

	@Autowired
	private EntityManager entityManager;

	@DisplayName("공개 상품 목록은 판매중 또는 품절이면서 삭제되지 않은 상품만 조회한다")
	@Test
	void findAllPublicProducts_whenMixedProducts_returnOnlyPublicProducts() {
		// given
		Product onSaleProduct = productRepository.save(createProduct("on-sale-product", ProductStatus.ON_SALE));
		Product soldOutProduct = productRepository.save(createProduct("sold-out-product", ProductStatus.SOLD_OUT));
		productRepository.save(createProduct("stopped-product", ProductStatus.STOPPED));
		Product deletedProduct = createProduct("deleted-product", ProductStatus.ON_SALE);
		deletedProduct.softDelete();
		productRepository.saveAndFlush(deletedProduct);
		entityManager.clear();

		// when
		List<Product> results = productRepository.findVisibleProducts(
			ProductStatus.publicStatuses()
		);

		// then
		assertThat(results)
			.extracting(Product::getId)
			.containsExactlyInAnyOrder(onSaleProduct.getId(), soldOutProduct.getId());
	}

	@DisplayName("공개 상품 상세 조회는 판매중지 또는 삭제 상품을 조회하지 않는다")
	@Test
	void findByIdPublicProduct_whenStoppedOrDeleted_returnEmpty() {
		// given
		Product stoppedProduct = productRepository.save(createProduct("stopped-product", ProductStatus.STOPPED));
		Product deletedProduct = createProduct("deleted-product", ProductStatus.ON_SALE);
		deletedProduct.softDelete();
		Product savedDeletedProduct = productRepository.saveAndFlush(deletedProduct);
		entityManager.clear();

		// when & then
		assertThat(productRepository.findVisibleProduct(
			stoppedProduct.getId(),
			ProductStatus.publicStatuses()
		)).isEmpty();
		assertThat(productRepository.findVisibleProduct(
			savedDeletedProduct.getId(),
			ProductStatus.publicStatuses()
		)).isEmpty();
	}

	private Product createProduct(String name, ProductStatus status) {
		return Product.builder()
			.name(name)
			.price(1000)
			.status(status)
			.build();
	}
}
