package com.commerce.stock.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.commerce.stock.domain.StockHistory;

public interface StockHistoryRepository extends JpaRepository<StockHistory, Long> {
	List<StockHistory> findAllByStockProductIdOrderByCreatedAtDesc(Long productId);
}
