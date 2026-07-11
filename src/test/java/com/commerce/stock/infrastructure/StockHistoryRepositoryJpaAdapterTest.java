package com.commerce.stock.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.TimeUnit;

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
import com.commerce.stock.domain.StockAdjustmentReason;
import com.commerce.stock.domain.StockHistory;
import com.commerce.stock.domain.repository.StockHistoryRepository;
import com.commerce.stock.domain.repository.StockRepository;

import com.commerce.stock.infrastructure.persistence.StockHistoryRepositoryAdapter;
import com.commerce.stock.infrastructure.persistence.StockRepositoryAdapter;
import jakarta.persistence.EntityManager;

@DataJpaTest
@Import({JpaConfig.class, StockRepositoryAdapter.class, StockHistoryRepositoryAdapter.class})
@ActiveProfiles("test")
class StockHistoryRepositoryJpaAdapterTest {

	@Autowired
	private StockHistoryRepository stockHistoryRepository;

	@Autowired
	private StockRepository stockRepository;

	@Autowired
	private JpaProductRepository productRepository;

	@Autowired
	private EntityManager entityManager;

	@DisplayName("재고 ID별 이력 조회는 다른 재고의 이력을 제외한다")
	@Test
	void findAllByStockIdOrderByCreatedAtDesc_whenDifferentStockHistories_excludeOtherStockHistories() {
		// given
		Product firstProduct = productRepository.save(createProduct("first-product"));
		Product secondProduct = productRepository.save(createProduct("second-product"));
		Stock firstStock = stockRepository.save(createStock(firstProduct, 10));
		Stock secondStock = stockRepository.save(createStock(secondProduct, 20));
		StockHistory firstHistory = stockHistoryRepository.save(createHistory(firstStock, 10));
		stockHistoryRepository.save(createHistory(secondStock, 20));
		entityManager.flush();
		entityManager.clear();

		// when
		List<StockHistory> results = stockHistoryRepository.findAllByStockIdOrderByCreatedAtDesc(
			firstStock.getId()
		);

		// then
		assertThat(results)
			.extracting(StockHistory::getId)
			.containsExactly(firstHistory.getId());
	}

	@DisplayName("재고 ID별 이력 조회는 최신순으로 정렬한다")
	@Test
	void findAllByStockIdOrderByCreatedAtDesc_whenMultipleHistories_returnLatestFirst() throws Exception {
		// given
		Product product = productRepository.save(createProduct("product"));
		Stock stock = stockRepository.save(createStock(product, 10));
		StockHistory firstHistory = stockHistoryRepository.save(
			createHistory(stock, 10, StockAdjustmentReason.INBOUND)
		);
		entityManager.flush();
		TimeUnit.MILLISECONDS.sleep(10);
		StockHistory latestHistory = stockHistoryRepository.save(
			createHistory(stock, -3, StockAdjustmentReason.DISPOSAL)
		);
		entityManager.flush();
		entityManager.clear();

		// when
		List<StockHistory> results = stockHistoryRepository.findAllByStockIdOrderByCreatedAtDesc(
			stock.getId()
		);

		// then
		assertThat(results)
			.extracting(StockHistory::getId)
			.containsExactly(latestHistory.getId(), firstHistory.getId());
		assertThat(results.get(0).getCreatedAt()).isAfter(results.get(1).getCreatedAt());
	}

	private Product createProduct(String name) {
		return Product.create(name, 1000, null, null, ProductStatus.ON_SALE);
	}

	private Stock createStock(Product product, int quantity) {
		return Stock.create(product.getId(), quantity);
	}

	private StockHistory createHistory(Stock stock, int quantityChange) {
		return createHistory(stock, quantityChange, StockAdjustmentReason.INBOUND);
	}

	private StockHistory createHistory(Stock stock, int quantityChange, StockAdjustmentReason reason) {
		return StockHistory.create(stock.getId(), quantityChange, reason, 10L);
	}
}
