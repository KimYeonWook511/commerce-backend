package com.commerce.stock.infrastructure;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.commerce.stock.domain.StockHistory;
import com.commerce.stock.domain.repository.StockHistoryRepository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class StockHistoryRepositoryAdapter implements StockHistoryRepository {

	private final JpaStockHistoryRepository jpaStockHistoryRepository;

	@Override
	public List<StockHistory> findAllByStockIdOrderByCreatedAtDesc(Long stockId) {
		return jpaStockHistoryRepository.findAllByStockIdOrderByCreatedAtDesc(stockId);
	}

	@Override
	public StockHistory save(StockHistory stockHistory) {
		return jpaStockHistoryRepository.save(stockHistory);
	}
}
