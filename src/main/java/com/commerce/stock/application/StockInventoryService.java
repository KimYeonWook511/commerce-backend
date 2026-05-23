package com.commerce.stock.application;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.stock.application.command.StockDecreaseBatchCommand;
import com.commerce.stock.application.result.StockDecreaseBatchResult;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.domain.repository.StockRepository;
import com.commerce.stock.exception.StockErrorCode;
import com.commerce.stock.exception.StockException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StockInventoryService {

	private final StockRepository stockRepository;

	@Transactional
	public void decrease(Long productId, int quantity) {
		// 주문/복구 흐름의 실제 재고 변경은 동시 요청 정합성을 위해 비관적 락으로 처리한다.
		Stock stock = stockRepository.findByProductIdWithPessimisticLock(productId)
			.orElseThrow(() -> new StockException(StockErrorCode.STOCK_NOT_FOUND));

		stock.decrease(quantity);
		log.info("재고 차감 productId={} quantity={} remaining={}", productId, quantity, stock.getQuantity());
	}

	@Transactional
	public void increase(Long productId, int quantity) {
		// 주문/복구 흐름의 실제 재고 변경은 동시 요청 정합성을 위해 비관적 락으로 처리한다.
		Stock stock = stockRepository.findByProductIdWithPessimisticLock(productId)
			.orElseThrow(() -> new StockException(StockErrorCode.STOCK_NOT_FOUND));

		stock.increase(quantity);
		log.info("재고 복구 productId={} quantity={} remaining={}", productId, quantity, stock.getQuantity());
	}

	@Transactional
	public StockDecreaseBatchResult decreaseBatch(StockDecreaseBatchCommand command) {
		Map<Long, Integer> quantitiesByProductId = command.getQuantitiesByProductId();
		List<Long> productIds = quantitiesByProductId.keySet().stream()
			.toList();
		// 여러 상품 재고를 한 트랜잭션에서 비관적 락으로 조회한 뒤 일괄 차감한다.
		List<Stock> findStocks = stockRepository.findAllByProductIdInWithPessimisticLock(productIds);
		if (findStocks.size() != productIds.size()) {
			throw new StockException(StockErrorCode.STOCK_NOT_FOUND);
		}

		Map<Long, Stock> stocksByProductId = findStocks.stream()
			.collect(Collectors.toMap(stock -> stock.getProduct().getId(), Function.identity()));

		for (Long productId : productIds) {
			Stock stock = stocksByProductId.get(productId);
			if (stock == null) {
				throw new StockException(StockErrorCode.STOCK_NOT_FOUND);
			}
			stock.decrease(quantitiesByProductId.get(productId));
		}

		log.info("재고 일괄 차감 productCount={}", quantitiesByProductId.size());
		return StockDecreaseBatchResult.from(quantitiesByProductId);
	}
}
