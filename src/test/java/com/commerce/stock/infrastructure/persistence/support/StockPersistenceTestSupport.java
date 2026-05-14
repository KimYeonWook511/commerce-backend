package com.commerce.stock.infrastructure.persistence.support;

import java.util.Optional;

import org.springframework.boot.test.context.TestComponent;

import com.commerce.stock.domain.Stock;
import com.commerce.stock.infrastructure.JpaStockRepository;

import lombok.RequiredArgsConstructor;
import com.commerce.support.CleanupOrder;
import com.commerce.support.PersistenceTestSupport;

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
