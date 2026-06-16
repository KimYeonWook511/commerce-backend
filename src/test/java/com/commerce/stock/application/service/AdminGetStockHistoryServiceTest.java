package com.commerce.stock.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.stock.application.dto.StockHistoryResult;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.domain.StockAdjustmentReason;
import com.commerce.stock.domain.StockHistory;
import com.commerce.stock.domain.repository.StockHistoryRepository;
import com.commerce.stock.domain.repository.StockRepository;

@ExtendWith(MockitoExtension.class)
class AdminGetStockHistoryServiceTest {

	@Mock
	private StockRepository stockRepository;

	@Mock
	private StockHistoryRepository stockHistoryRepository;

	@InjectMocks
	private AdminGetStockHistoryService adminGetStockHistoryService;

	@DisplayName("상품별 재고 이력은 최신순 결과로 반환된다")
	@Test
	void getHistoriesByProductId_whenStockExists_returnHistoriesOrderByCreatedAtDesc() {
		// given
		Product product = createProduct(1L, "product-1", 1000);
		Stock stock = createStock(product, 10);
		ReflectionTestUtils.setField(stock, "id", 100L);
		StockHistory latestHistory = createStockHistory(2L, stock, -3, StockAdjustmentReason.DISPOSAL,
			LocalDateTime.of(2026, 4, 30, 12, 30));
		StockHistory firstHistory = createStockHistory(1L, stock, 10, StockAdjustmentReason.INBOUND,
			LocalDateTime.of(2026, 4, 30, 12, 0));

		given(stockRepository.findByProductId(1L)).willReturn(Optional.of(stock));
		given(stockHistoryRepository.findAllByStockIdOrderByCreatedAtDesc(100L))
			.willReturn(List.of(latestHistory, firstHistory));

		// when
		List<StockHistoryResult> results = adminGetStockHistoryService.getHistoriesByProductId(1L);

		// then
		assertThat(results).hasSize(2);
		assertThat(results.get(0).getHistoryId()).isEqualTo(2L);
		assertThat(results.get(0).getQuantityChange()).isEqualTo(-3);
		assertThat(results.get(0).getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 4, 30, 12, 30));
		assertThat(results.get(1).getHistoryId()).isEqualTo(1L);
		assertThat(results.get(1).getQuantityChange()).isEqualTo(10);
		assertThat(results.get(1).getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 4, 30, 12, 0));
	}

	private Stock createStock(Product product, int quantity) {
		return Stock.builder()
			.productId(product.getId())
			.quantity(quantity)
			.build();
	}

	private StockHistory createStockHistory(
		Long id,
		Stock stock,
		int quantityChange,
		StockAdjustmentReason reason,
		LocalDateTime createdAt
	) {
		StockHistory history = StockHistory.builder()
			.stockId(stock.getId())
			.quantityChange(quantityChange)
			.reason(reason)
			.adminMemberId(10L)
			.build();
		ReflectionTestUtils.setField(history, "id", id);
		ReflectionTestUtils.setField(history, "createdAt", createdAt);
		return history;
	}

	private Product createProduct(Long id, String name, int price) {
		Product product = Product.builder()
			.name(name)
			.price(price)
			.status(ProductStatus.ON_SALE)
			.build();
		ReflectionTestUtils.setField(product, "id", id);
		return product;
	}
}
