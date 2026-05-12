package com.commerce.stock.infrastructure;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.commerce.stock.domain.StockHistory;

public interface JpaStockHistoryRepository extends JpaRepository<StockHistory, Long> {

	List<StockHistory> findAllByStockProductIdOrderByCreatedAtDesc(Long productId);
}
