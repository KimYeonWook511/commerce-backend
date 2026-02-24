package com.commerce.stock.service;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.commerce.stock.domain.Stock;
import com.commerce.stock.exception.StockErrorCode;
import com.commerce.stock.exception.StockException;
import com.commerce.stock.repository.StockRepository;
import com.commerce.stock.service.command.StockDecreaseBatchCommand;
import com.commerce.stock.service.result.StockDecreaseBatchResult;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.concurrent.locks.ReentrantLock;

import javax.sql.DataSource;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class StockService {

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

	@Transactional
	public void decreaseWithPessimisticLock(Long productId, int quantity) {
		Stock stock = stockRepository.findByProductIdWithPessimisticLock(productId)
			.orElseThrow(() -> new StockException(StockErrorCode.STOCK_NOT_FOUND));

		stock.decrease(quantity);
	}

	@Transactional
	public void increaseWithPessimisticLock(Long productId, int quantity) {
		Stock stock = stockRepository.findByProductIdWithPessimisticLock(productId)
			.orElseThrow(() -> new StockException(StockErrorCode.STOCK_NOT_FOUND));

		stock.increase(quantity);
	}

	@Transactional
	public StockDecreaseBatchResult decreaseBatchWithPessimisticLock(StockDecreaseBatchCommand command) {
		// 재고 조회 (X-Lock)
		Map<Long, Integer> quantitiesByProductId = command.getQuantitiesByProductId();
		List<Long> productIds = quantitiesByProductId.keySet().stream()
			// .sorted() // in절에 쓰이는 list는 정렬해도 락 순서를 보장하지 않음
			.toList();
		List<Stock> findStocks = stockRepository.findAllByProductIdInWithPessimisticLock(productIds);
		if (findStocks.size() != productIds.size()) {
			throw new StockException(StockErrorCode.STOCK_NOT_FOUND);
		}

		Map<Long, Stock> stocksByProductId = findStocks.stream()
			.collect(Collectors.toMap(stock -> stock.getProduct().getId(), Function.identity()));

		// 재고 차감
		for (Long productId : productIds) {
			Stock stock = stocksByProductId.get(productId);
			if (stock == null) {
				throw new StockException(StockErrorCode.STOCK_NOT_FOUND);
			}
			stock.decrease(quantitiesByProductId.get(productId));
		}

		// void로 끝낼까..
		return StockDecreaseBatchResult.from(quantitiesByProductId);
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
