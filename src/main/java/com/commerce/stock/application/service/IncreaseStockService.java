package com.commerce.stock.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.stock.domain.Stock;
import com.commerce.stock.domain.repository.StockRepository;
import com.commerce.stock.domain.exception.StockErrorCode;
import com.commerce.stock.domain.exception.StockException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class IncreaseStockService {

	private final StockRepository stockRepository;

	@Transactional
	public void increase(Long productId, int quantity) {
		// 주문/복구 흐름의 실제 재고 변경은 동시 요청 정합성을 위해 비관적 락으로 처리한다.
		Stock stock = stockRepository.findByProductIdWithPessimisticLock(productId)
			.orElseThrow(() -> new StockException(StockErrorCode.STOCK_NOT_FOUND));

		stock.increase(quantity);
	}
}
