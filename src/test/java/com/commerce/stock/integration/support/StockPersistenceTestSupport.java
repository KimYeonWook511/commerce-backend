package com.commerce.stock.integration.support;

import java.util.Optional;

import org.springframework.boot.test.context.TestComponent;

import com.commerce.stock.domain.Stock;
import com.commerce.stock.infrastructure.JpaStockRepository;
import com.commerce.test.support.CleanupOrder;
import com.commerce.test.support.PersistenceTestSupport;

import lombok.RequiredArgsConstructor;

@TestComponent
@RequiredArgsConstructor
public class StockPersistenceTestSupport implements PersistenceTestSupport {

	private final JpaStockRepository stockRepository;

	@Override
	public CleanupOrder cleanupOrder() {
		return CleanupOrder.STOCK;
	}

	@Override
	public void deleteAllInBatch() {
		stockRepository.deleteAllInBatch();
	}

	public Stock save(Stock stock) {
		return stockRepository.save(stock);
	}

	public Optional<Stock> findByProductId(Long productId) {
		return stockRepository.findByProductId(productId);
	}
}
