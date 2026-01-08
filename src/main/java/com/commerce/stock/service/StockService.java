package com.commerce.stock.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.commerce.stock.domain.Stock;
import com.commerce.stock.exception.StockErrorCode;
import com.commerce.stock.exception.StockException;
import com.commerce.stock.repository.StockRepository;

import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StockService {

	private final StockRepository stockRepository;
	private final TransactionTemplate transactionTemplate;

	@Transactional
	public void decrease(Long productId, int quantity) {
		Stock stock = stockRepository.findByProductId(productId)
			.orElseThrow(() -> new StockException(StockErrorCode.STOCK_NOT_FOUND));

		stock.decrease(quantity);
	}

	@Transactional
	public synchronized void decreaseWithSynchronized(Long productId, int quantity) {
		Stock stock = stockRepository.findByProductId(productId)
			.orElseThrow(() -> new StockException(StockErrorCode.STOCK_NOT_FOUND));

		stock.decrease(quantity);
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public synchronized void decreaseWithSynchronizedAndTransaction(Long productId, int quantity) {
		// boolean actualTransactionActive = TransactionSynchronizationManager.isActualTransactionActive();
		// boolean currentTransactionReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
		// System.out.println("tx active = " + actualTransactionActive);
		// System.out.println("tx readOnly = " + currentTransactionReadOnly);
		transactionTemplate.execute(status -> {
			Stock stock = stockRepository.findByProductId(productId)
				.orElseThrow(() -> new StockException(StockErrorCode.STOCK_NOT_FOUND));

			stock.decrease(quantity);
			return null;
		});
	}

}
