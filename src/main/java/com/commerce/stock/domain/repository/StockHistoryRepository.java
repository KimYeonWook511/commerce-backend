package com.commerce.stock.domain.repository;

import java.util.List;

import com.commerce.stock.domain.StockHistory;

public interface StockHistoryRepository {

	List<StockHistory> findAllByStockIdOrderByCreatedAtDesc(Long stockId);

	StockHistory save(StockHistory stockHistory);
}
