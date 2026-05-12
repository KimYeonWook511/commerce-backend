package com.commerce.stock.infrastructure;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.commerce.stock.domain.Stock;
import com.commerce.stock.domain.repository.StockRepository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class StockRepositoryAdapter implements StockRepository {

	private final JpaStockRepository jpaStockRepository;

	@Override
	public Optional<Stock> findByProductId(Long productId) {
		return jpaStockRepository.findByProductId(productId);
	}

	@Override
	public Optional<Stock> findByProductIdWithPessimisticLock(Long productId) {
		return jpaStockRepository.findByProductIdWithPessimisticLock(productId);
	}

	@Override
	public List<Stock> findAllByProductIdInWithPessimisticLock(List<Long> productIds) {
		return jpaStockRepository.findAllByProductIdInWithPessimisticLock(productIds);
	}

	@Override
	public Stock save(Stock stock) {
		return jpaStockRepository.save(stock);
	}
}
