package com.commerce.stock.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.commerce.stock.domain.Stock;
import com.commerce.stock.domain.repository.StockRepository;
import com.commerce.stock.domain.exception.StockErrorCode;
import com.commerce.stock.domain.exception.StockException;

@ExtendWith(MockitoExtension.class)
class StockConcurrencyServiceTest {

	@Mock
	private StockRepository stockRepository;

	@InjectMocks
	private StockConcurrencyService stockConcurrencyService;

	@DisplayName("재고가 존재하면 차감된다")
	@Test
	void decrease_whenStockExists_decreaseQuantity() {
		// given
		Stock stock = createStock(10);
		given(stockRepository.findByProductId(1L)).willReturn(Optional.of(stock));

		// when
		stockConcurrencyService.decrease(1L, 3);

		// then
		assertThat(stock.getQuantity()).isEqualTo(7);
	}

	@DisplayName("재고가 없으면 예외가 발생한다")
	@Test
	void decrease_whenStockNotFound_throwException() {
		// given
		given(stockRepository.findByProductId(1L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> stockConcurrencyService.decrease(1L, 1))
			.isInstanceOf(StockException.class)
			.satisfies(exception -> {
				StockException stockException = (StockException) exception;
				assertThat(stockException.getErrorCode()).isEqualTo(StockErrorCode.STOCK_NOT_FOUND);
			});
	}

	@DisplayName("재고가 부족하면 예외가 발생한다")
	@Test
	void decrease_whenOutOfStock_throwException() {
		// given
		Stock stock = createStock(1);
		given(stockRepository.findByProductId(1L)).willReturn(Optional.of(stock));

		// when & then
		assertThatThrownBy(() -> stockConcurrencyService.decrease(1L, 2))
			.isInstanceOf(StockException.class)
			.satisfies(exception -> {
				StockException stockException = (StockException) exception;
				assertThat(stockException.getErrorCode()).isEqualTo(StockErrorCode.OUT_OF_STOCK);
			});
	}

	private Stock createStock(int quantity) {
		return Stock.builder()
			.quantity(quantity)
			.build();
	}
}
