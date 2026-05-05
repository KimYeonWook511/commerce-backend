package com.commerce.stock.domain.repository;

import java.util.List;
import java.util.Optional;

import com.commerce.stock.domain.Stock;

public interface StockRepository {

	Optional<Stock> findByProductId(Long productId);

	Optional<Stock> findByProductIdWithPessimisticLock(Long productId);

	List<Stock> findAllByProductIdInWithPessimisticLock(List<Long> productIds);

	Stock save(Stock stock);
}
