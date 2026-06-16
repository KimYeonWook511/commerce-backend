package com.commerce.stock.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.product.domain.Product;
import com.commerce.product.domain.exception.ProductErrorCode;
import com.commerce.product.domain.exception.ProductException;
import com.commerce.product.domain.repository.ProductRepository;
import com.commerce.stock.application.dto.AdminStockCreateCommand;
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
public class AdminInitializeStockService {

	private final StockRepository stockRepository;
	private final StockHistoryRepository stockHistoryRepository;
	private final ProductRepository productRepository;

	@Transactional
	public AdminStockResult createInitialStock(AdminStockCreateCommand command) {
		if (command.getQuantity() < 0) {
			throw new StockException(StockErrorCode.INVALID_STOCK_QUANTITY);
		}

		Product product = productRepository.findNotDeletedProduct(command.getProductId())
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));

		if (stockRepository.findByProductId(command.getProductId()).isPresent()) {
			throw new StockException(StockErrorCode.STOCK_ALREADY_EXISTS);
		}

		Stock stock = Stock.builder()
			.productId(product.getId())
			.quantity(command.getQuantity())
			.build();
		Stock savedStock = stockRepository.save(stock);
		saveHistory(savedStock.getId(), command.getQuantity(), command.getReason(), command.getAdminMemberId());
		log.info("재고 초기 설정 productId={} quantity={} reason={} adminMemberId={}",
			command.getProductId(), command.getQuantity(), command.getReason(), command.getAdminMemberId());

		return AdminStockResult.from(savedStock);
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
