package com.commerce.stock.application.service;

import java.sql.SQLException;
import java.util.concurrent.locks.ReentrantLock;

import javax.sql.DataSource;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.commerce.stock.domain.Stock;
import com.commerce.stock.domain.repository.StockRepository;
import com.commerce.stock.domain.exception.StockErrorCode;
import com.commerce.stock.domain.exception.StockException;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockDecreaseConcurrencyService {

	private static final int MAX_RETRY = 5;

	private final StockRepository stockRepository;
	private final TransactionTemplate transactionTemplate;
	private final ReentrantLock reentrantLock = new ReentrantLock();
	private final DataSource dataSource;

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
		HikariDataSource hikari;
		HikariPoolMXBean pool;
		try {
			hikari = dataSource.unwrap(HikariDataSource.class);
			pool = hikari.getHikariPoolMXBean();
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}

		log.debug(">> BEFORE new tx | thread={} | active={}, idle={}, total={}, waiting={}",
			Thread.currentThread().getName(),
			pool.getActiveConnections(),
			pool.getIdleConnections(),
			pool.getTotalConnections(),
			pool.getThreadsAwaitingConnection());
		transactionTemplate.execute(status -> {
			Stock stock = stockRepository.findByProductId(productId)
				.orElseThrow(() -> new StockException(StockErrorCode.STOCK_NOT_FOUND));

			stock.decrease(quantity);
			return null;
		});
		log.debug("<< AFTER new tx | thread={} | active={}, idle={}, total={}, waiting={}",
			Thread.currentThread().getName(),
			pool.getActiveConnections(),
			pool.getIdleConnections(),
			pool.getTotalConnections(),
			pool.getThreadsAwaitingConnection());
	}
}
