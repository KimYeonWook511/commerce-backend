package com.commerce.stock.application.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.stock.application.dto.StockDecreaseBatchCommand;
import com.commerce.stock.application.dto.StockDecreaseBatchResult;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.domain.repository.StockRepository;
import com.commerce.stock.domain.exception.StockErrorCode;
import com.commerce.stock.domain.exception.StockException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DecreaseStockBatchService {

	private final StockRepository stockRepository;

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
			.collect(Collectors.toMap(stock -> stock.getProductId(), Function.identity()));

		for (Long productId : productIds) {
			Stock stock = stocksByProductId.get(productId);
			if (stock == null) {
				throw new StockException(StockErrorCode.STOCK_NOT_FOUND);
			}
			stock.decrease(quantitiesByProductId.get(productId));
		}

		return StockDecreaseBatchResult.from(quantitiesByProductId);
	}
}
