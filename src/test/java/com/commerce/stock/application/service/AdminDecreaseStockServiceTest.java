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
import com.commerce.stock.domain.exception.StockErrorCode;
import com.commerce.stock.domain.exception.StockException;

@ExtendWith(MockitoExtension.class)
class AdminDecreaseStockServiceTest {

	@Mock
	private StockRepository stockRepository;

	@Mock
	private StockHistoryRepository stockHistoryRepository;

	@InjectMocks
	private AdminDecreaseStockService adminDecreaseStockService;

	@DisplayName("관리자 재고 감소는 비관적 락으로 조회하고 음수 이력을 저장한다")
	@Test
	void decreaseByAdmin_whenStockExists_decreaseQuantityAndSaveNegativeHistory() {
		// given
		Product product = createProduct(1L, "product-1", 1000);
		Stock stock = createStock(product, 10);
		ReflectionTestUtils.setField(stock, "id", 100L);
		AdminStockAdjustCommand command = AdminStockAdjustCommand.builder()
			.productId(1L)
			.quantity(3)
			.reason(StockAdjustmentReason.DISPOSAL)
			.adminMemberId(10L)
			.build();

		given(stockRepository.findByProductIdWithPessimisticLock(1L)).willReturn(Optional.of(stock));

		// when
		AdminStockResult result = adminDecreaseStockService.decreaseByAdmin(command);

		// then
		assertThat(stock.getQuantity()).isEqualTo(7);
		assertThat(result.getQuantity()).isEqualTo(7);

		ArgumentCaptor<StockHistory> historyCaptor = ArgumentCaptor.forClass(StockHistory.class);
		then(stockHistoryRepository).should().save(historyCaptor.capture());
		assertThat(historyCaptor.getValue().getQuantityChange()).isEqualTo(-3);
		assertThat(historyCaptor.getValue().getReason()).isEqualTo(StockAdjustmentReason.DISPOSAL);
	}

	@DisplayName("관리자 재고 감소 수량이 현재 재고보다 크면 예외가 발생한다")
	@Test
	void decreaseByAdmin_whenOutOfStock_throwException() {
		// given
		Product product = createProduct(1L, "product-1", 1000);
		Stock stock = createStock(product, 1);
		AdminStockAdjustCommand command = AdminStockAdjustCommand.builder()
			.productId(1L)
			.quantity(2)
			.reason(StockAdjustmentReason.DISPOSAL)
			.adminMemberId(10L)
			.build();

		given(stockRepository.findByProductIdWithPessimisticLock(1L)).willReturn(Optional.of(stock));

		// when & then
		assertThatThrownBy(() -> adminDecreaseStockService.decreaseByAdmin(command))
			.isInstanceOf(StockException.class)
			.satisfies(exception -> {
				StockException stockException = (StockException) exception;
				assertThat(stockException.getErrorCode()).isEqualTo(StockErrorCode.OUT_OF_STOCK);
			});
		then(stockHistoryRepository).should(never()).save(any(StockHistory.class));
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
