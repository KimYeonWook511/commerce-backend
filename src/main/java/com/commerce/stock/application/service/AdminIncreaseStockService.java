package com.commerce.stock.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.stock.application.dto.AdminStockAdjustCommand;
import com.commerce.stock.application.dto.AdminStockResult;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.domain.StockAdjustmentReason;
import com.commerce.stock.domain.StockHistory;
import com.commerce.stock.domain.repository.StockHistoryRepository;
import com.commerce.stock.domain.repository.StockRepository;
import com.commerce.stock.domain.exception.StockErrorCode;
import com.commerce.stock.domain.exception.StockException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminIncreaseStockService {

	private final StockRepository stockRepository;
	private final StockHistoryRepository stockHistoryRepository;

	@Transactional
	public AdminStockResult increaseByAdmin(AdminStockAdjustCommand command) {
		Stock stock = stockRepository.findByProductIdWithPessimisticLock(command.getProductId())
			.orElseThrow(() -> new StockException(StockErrorCode.STOCK_NOT_FOUND));

		stock.increase(command.getQuantity());
		saveHistory(stock.getId(), command.getQuantity(), command.getReason(), command.getAdminMemberId());
		log.info("재고 운영 증가 productId={} quantity={} reason={} adminMemberId={} newTotal={}",
			command.getProductId(), command.getQuantity(), command.getReason(), command.getAdminMemberId(), stock.getQuantity());

		return AdminStockResult.from(stock);
	}

	private void saveHistory(Long stockId, int quantityChange, StockAdjustmentReason reason, Long adminMemberId) {
		StockHistory history = StockHistory.builder()
			.stockId(stockId)
			.quantityChange(quantityChange)
			.reason(reason)
			.adminMemberId(adminMemberId)
			.build();

		stockHistoryRepository.save(history);
	}
}
