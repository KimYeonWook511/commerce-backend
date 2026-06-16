package com.commerce.stock.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.stock.application.dto.AdminStockAdjustCommand;
import com.commerce.stock.application.dto.AdminStockResult;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.domain.StockAdjustmentReason;
import com.commerce.stock.domain.StockHistory;
import com.commerce.stock.domain.repository.StockHistoryRepository;
import com.commerce.stock.domain.repository.StockRepository;

@ExtendWith(MockitoExtension.class)
class AdminIncreaseStockServiceTest {

	@Mock
	private StockRepository stockRepository;

	@Mock
	private StockHistoryRepository stockHistoryRepository;

	@InjectMocks
	private AdminIncreaseStockService adminIncreaseStockService;

	@DisplayName("관리자 재고 증가는 비관적 락으로 조회하고 양수 이력을 저장한다")
	@Test
	void increaseByAdmin_whenStockExists_increaseQuantityAndSavePositiveHistory() {
		// given
		Product product = createProduct(1L, "product-1", 1000);
		Stock stock = createStock(product, 10);
		ReflectionTestUtils.setField(stock, "id", 100L);
		AdminStockAdjustCommand command = AdminStockAdjustCommand.builder()
			.productId(1L)
			.quantity(5)
			.reason(StockAdjustmentReason.INBOUND)
			.adminMemberId(10L)
			.build();

		given(stockRepository.findByProductIdWithPessimisticLock(1L)).willReturn(Optional.of(stock));

		// when
		AdminStockResult result = adminIncreaseStockService.increaseByAdmin(command);

		// then
		assertThat(stock.getQuantity()).isEqualTo(15);
		assertThat(result.getQuantity()).isEqualTo(15);

		ArgumentCaptor<StockHistory> historyCaptor = ArgumentCaptor.forClass(StockHistory.class);
		then(stockHistoryRepository).should().save(historyCaptor.capture());
		assertThat(historyCaptor.getValue().getQuantityChange()).isEqualTo(5);
		assertThat(historyCaptor.getValue().getReason()).isEqualTo(StockAdjustmentReason.INBOUND);
	}

	private Stock createStock(Product product, int quantity) {
		return Stock.builder()
			.productId(product.getId())
			.quantity(quantity)
			.build();
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
