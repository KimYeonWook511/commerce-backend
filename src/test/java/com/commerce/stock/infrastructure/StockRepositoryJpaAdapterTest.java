package com.commerce.stock.infrastructure;

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
import com.commerce.product.infrastructure.persistence.JpaProductRepository;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.domain.repository.StockRepository;

import com.commerce.stock.infrastructure.persistence.StockRepositoryAdapter;
import jakarta.persistence.EntityManager;

@DataJpaTest
@Import({JpaConfig.class, StockRepositoryAdapter.class})
@ActiveProfiles("test")
class StockRepositoryJpaAdapterTest {

	@Autowired
	private StockRepository stockRepository;

	@Autowired
	private JpaProductRepository productRepository;

	@Autowired
	private EntityManager entityManager;

	@DisplayName("상품 ID로 재고를 조회한다")
	@Test
	void findByProductId_whenStockExists_returnStock() {
		// given
		Product product = productRepository.save(createProduct("stock-product"));
		Stock saved = stockRepository.save(createStock(product, 10));
		entityManager.flush();
		entityManager.clear();

		// when
		Stock result = stockRepository.findByProductId(product.getId()).orElseThrow();

		// then
		assertThat(result.getId()).isEqualTo(saved.getId());
		assertThat(result.getQuantity()).isEqualTo(10);
	}

	@DisplayName("비관적 락 조회 조건에 맞는 재고를 조회한다")
	@Test
	void findByProductIdWithPessimisticLock_whenStockExists_returnStock() {
		// given
		Product product = productRepository.save(createProduct("locked-stock-product"));
		Stock saved = stockRepository.save(createStock(product, 5));
		entityManager.flush();
		entityManager.clear();

		// when
		Stock result = stockRepository.findByProductIdWithPessimisticLock(product.getId()).orElseThrow();

		// then
		assertThat(result.getId()).isEqualTo(saved.getId());
		assertThat(result.getProductId()).isEqualTo(product.getId());
	}

	@DisplayName("여러 상품 ID로 비관적 락 재고를 조회한다")
	@Test
	void findAllByProductIdInWithPessimisticLock_whenStocksExist_returnStocks() {
		// given
		Product firstProduct = productRepository.save(createProduct("first-stock-product"));
		Product secondProduct = productRepository.save(createProduct("second-stock-product"));
		stockRepository.save(createStock(firstProduct, 10));
		stockRepository.save(createStock(secondProduct, 20));
		entityManager.flush();
		entityManager.clear();

		// when
		List<Stock> results = stockRepository.findAllByProductIdInWithPessimisticLock(
			List.of(firstProduct.getId(), secondProduct.getId())
		);

		// then
		assertThat(results)
			.extracting(stock -> stock.getProductId())
			.containsExactlyInAnyOrder(firstProduct.getId(), secondProduct.getId());
	}

	private Stock createStock(Product product, int quantity) {
		return Stock.create(product.getId(), quantity);
	}

	private Product createProduct(String name) {
		return Product.create(name, 1000, null, null, ProductStatus.ON_SALE);
	}
}
