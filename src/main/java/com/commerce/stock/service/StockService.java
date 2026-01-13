package com.commerce.stock.service;

import org.springframework.dao.OptimisticLockingFailureException;
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

import java.util.concurrent.locks.ReentrantLock;

import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StockService {

	private static final int MAX_RETRY = 5;

	private final StockRepository stockRepository;
	private final TransactionTemplate transactionTemplate;
	private final ReentrantLock reentrantLock = new ReentrantLock();

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
		decreaseWithNewTransaction(productId, quantity);
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void decreaseWithReentrantLockAndTransaction(Long productId, int quantity) {
		reentrantLock.lock();
		try {
			decreaseWithNewTransaction(productId, quantity);
		} finally {
			reentrantLock.unlock();
		}
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void decreaseWithOptimisticLock(Long productId, int quantity) {
		// AOP로도 가능할듯
		for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
			try {
				decreaseWithNewTransaction(productId, quantity);
				break;
			} catch (OptimisticLockingFailureException ex) {
				if (attempt == MAX_RETRY) {
					throw new StockException(StockErrorCode.OPTIMISTIC_LOCK_FAILED);
				}
			}
		}
	}

	private void decreaseWithNewTransaction(Long productId, int quantity) {
		transactionTemplate.execute(status -> {
			Stock stock = stockRepository.findByProductId(productId)
				.orElseThrow(() -> new StockException(StockErrorCode.STOCK_NOT_FOUND));

			stock.decrease(quantity);
			return null;
		});
	}

}
