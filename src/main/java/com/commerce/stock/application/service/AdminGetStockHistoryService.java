package com.commerce.stock.application.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.stock.application.dto.StockHistoryResult;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.domain.repository.StockHistoryRepository;
import com.commerce.stock.domain.repository.StockRepository;
import com.commerce.stock.domain.exception.StockErrorCode;
import com.commerce.stock.domain.exception.StockException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AdminGetStockHistoryService {

	private final StockRepository stockRepository;
	private final StockHistoryRepository stockHistoryRepository;

	@Transactional(readOnly = true)
	public List<StockHistoryResult> getHistoriesByProductId(Long productId) {
		Stock stock = stockRepository.findByProductId(productId)
			.orElseThrow(() -> new StockException(StockErrorCode.STOCK_NOT_FOUND));

		return stockHistoryRepository.findAllByStockIdOrderByCreatedAtDesc(stock.getId()).stream()
			.map(history -> StockHistoryResult.from(history, productId))
			.toList();
	}
}
