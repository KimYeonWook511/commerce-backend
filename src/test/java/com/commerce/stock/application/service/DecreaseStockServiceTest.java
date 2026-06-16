package com.commerce.stock.application.service;

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

@ExtendWith(MockitoExtension.class)
class DecreaseStockServiceTest {

	@Mock
	private StockRepository stockRepository;

	@InjectMocks
	private DecreaseStockService decreaseStockService;

	@DisplayName("비관적 락으로 조회한 재고는 차감된다")
	@Test
	void decrease_whenStockExists_decreaseQuantity() {
		// given
		Stock stock = createStock(10);
		given(stockRepository.findByProductIdWithPessimisticLock(1L)).willReturn(Optional.of(stock));

		// when
		decreaseStockService.decrease(1L, 4);

		// then
		assertThat(stock.getQuantity()).isEqualTo(6);
	}

	private Stock createStock(int quantity) {
		return Stock.builder()
			.quantity(quantity)
			.build();
	}
}
