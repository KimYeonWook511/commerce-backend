package com.commerce.stock.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.commerce.stock.exception.StockErrorCode;
import com.commerce.stock.exception.StockException;

class StockTest {

	@DisplayName("재고가 충분하면 차감된다")
	@Test
	void decrease_whenQuantityEnough_decreaseStock() {
		// given
		Stock stock = createStock(10);

		// when
		stock.decrease(3);

		// then
		assertThat(stock.getQuantity()).isEqualTo(7);
	}

	@DisplayName("재고가 부족하면 예외가 발생한다")
	@Test
	void decrease_whenOutOfStock_throwException() {
		// given
		Stock stock = createStock(2);

		// when & then
		assertThatThrownBy(() -> stock.decrease(3))
			.isInstanceOf(StockException.class)
			.satisfies(exception -> {
				StockException stockException = (StockException) exception;
				assertThat(stockException.getErrorCode()).isEqualTo(StockErrorCode.OUT_OF_STOCK);
			});
	}

	@DisplayName("차감 수량이 0 이하이면 예외가 발생한다")
	@Test
	void decrease_whenInvalidQuantity_throwException() {
		// given
		Stock stock = createStock(5);

		// when & then
		assertThatThrownBy(() -> stock.decrease(0))
			.isInstanceOf(StockException.class)
			.satisfies(exception -> {
				StockException stockException = (StockException) exception;
				assertThat(stockException.getErrorCode()).isEqualTo(StockErrorCode.INVALID_QUANTITY);
			});
	}

	private Stock createStock(int quantity) {
		return Stock.builder()
			.quantity(quantity)
			.build();
	}
}
