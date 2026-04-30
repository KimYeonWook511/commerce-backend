package com.commerce.stock.repository;

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
import com.commerce.product.repository.ProductRepository;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.domain.StockAdjustmentReason;
import com.commerce.stock.domain.StockHistory;

import jakarta.persistence.EntityManager;

@DataJpaTest
@Import(JpaConfig.class)
@ActiveProfiles("test")
class StockHistoryRepositoryTest {

	@Autowired
	private StockHistoryRepository stockHistoryRepository;

	@Autowired
	private StockRepository stockRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private EntityManager entityManager;

	@DisplayName("상품별 재고 이력 조회는 다른 상품의 이력을 제외한다")
	@Test
	void findAllByStockProductIdOrderByCreatedAtDesc_whenDifferentProductHistories_excludeOtherProductHistories() {
		// given
		Stock firstStock = saveStock("first-product", 10);
		Stock secondStock = saveStock("second-product", 20);
		StockHistory firstHistory = stockHistoryRepository.save(createHistory(firstStock, 10));
		stockHistoryRepository.save(createHistory(secondStock, 20));
		stockHistoryRepository.flush();
		entityManager.clear();

		// when
		List<StockHistory> results = stockHistoryRepository.findAllByStockProductIdOrderByCreatedAtDesc(
			firstStock.getProduct().getId()
		);

		// then
		assertThat(results)
			.extracting(StockHistory::getId)
			.containsExactly(firstHistory.getId());
	}

	@DisplayName("상품별 재고 이력 조회는 최신순으로 정렬한다")
	@Test
	void findAllByStockProductIdOrderByCreatedAtDesc_whenMultipleHistories_returnLatestFirst() throws Exception {
		// given
		Stock stock = saveStock("product", 10);
		StockHistory firstHistory = stockHistoryRepository.saveAndFlush(
			createHistory(stock, 10, StockAdjustmentReason.INBOUND)
		);
		TimeUnit.MILLISECONDS.sleep(10);
		StockHistory latestHistory = stockHistoryRepository.saveAndFlush(
			createHistory(stock, -3, StockAdjustmentReason.DISPOSAL)
		);
		entityManager.clear();

		// when
		List<StockHistory> results = stockHistoryRepository.findAllByStockProductIdOrderByCreatedAtDesc(
			stock.getProduct().getId()
		);

		// then
		assertThat(results)
			.extracting(StockHistory::getId)
			.containsExactly(latestHistory.getId(), firstHistory.getId());
		assertThat(results.get(0).getCreatedAt()).isAfter(results.get(1).getCreatedAt());
	}

	private Stock saveStock(String productName, int quantity) {
		Product product = productRepository.save(Product.builder()
			.name(productName)
			.price(1000)
			.status(ProductStatus.ON_SALE)
			.build());

		return stockRepository.save(Stock.builder()
			.product(product)
			.quantity(quantity)
			.build());
	}

	private StockHistory createHistory(Stock stock, int quantityChange) {
		return createHistory(stock, quantityChange, StockAdjustmentReason.INBOUND);
	}

	private StockHistory createHistory(Stock stock, int quantityChange, StockAdjustmentReason reason) {
		return StockHistory.builder()
			.stock(stock)
			.quantityChange(quantityChange)
			.reason(reason)
			.adminMemberId(10L)
			.build();
	}
}
