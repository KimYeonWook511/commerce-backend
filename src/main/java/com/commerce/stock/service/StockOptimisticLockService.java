package com.commerce.stock.service;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.commerce.stock.domain.Stock;
import com.commerce.stock.exception.StockErrorCode;
import com.commerce.stock.exception.StockException;
import com.commerce.stock.repository.StockRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StockOptimisticLockService {

	private static final int MAX_RETRY = 5;

	private final StockRepository stockRepository;
	private final TransactionTemplate transactionTemplate;

	public void decreaseWithOptimisticLock(Long productId, int quantity) {
		// AOP로도 가능할듯
		for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
			try {
				transactionTemplate.execute(status -> {
					Stock stock = stockRepository.findByProductId(productId)
						.orElseThrow(() -> new StockException(StockErrorCode.STOCK_NOT_FOUND));

					stock.decrease(quantity);
					return null;
				});
			} catch (OptimisticLockingFailureException ex) {
				if (attempt == MAX_RETRY) {
					throw new StockException(StockErrorCode.OPTIMISTIC_LOCK_FAILED);
				}
			}
		}
	}
}
