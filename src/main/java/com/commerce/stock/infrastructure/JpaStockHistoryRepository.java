package com.commerce.stock.infrastructure;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.commerce.stock.domain.StockHistory;
import com.commerce.stock.domain.repository.StockHistoryRepository;

public interface JpaStockHistoryRepository extends JpaRepository<StockHistory, Long>, StockHistoryRepository {

	List<StockHistory> findAllByStockProductIdOrderByCreatedAtDesc(Long productId);
}
