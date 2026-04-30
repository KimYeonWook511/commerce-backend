package com.commerce.stock.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StockHistoryTest {

	@DisplayName("양수 변경 수량으로 재고 이력을 생성한다")
	@Test
	void create_whenQuantityChangePositive_createStockHistory() {
		// given
		Stock stock = createStock();

		// when
		StockHistory stockHistory = StockHistory.builder()
			.stock(stock)
			.quantityChange(10)
			.reason(StockAdjustmentReason.INBOUND)
			.adminMemberId(1L)
			.build();

		// then
		assertThat(stockHistory.getStock()).isEqualTo(stock);
		assertThat(stockHistory.getQuantityChange()).isEqualTo(10);
		assertThat(stockHistory.getReason()).isEqualTo(StockAdjustmentReason.INBOUND);
		assertThat(stockHistory.getAdminMemberId()).isEqualTo(1L);
	}

	@DisplayName("음수 변경 수량으로 재고 이력을 생성한다")
	@Test
	void create_whenQuantityChangeNegative_createStockHistory() {
		// given
		Stock stock = createStock();

		// when
		StockHistory stockHistory = StockHistory.builder()
			.stock(stock)
			.quantityChange(-3)
			.reason(StockAdjustmentReason.DISPOSAL)
			.adminMemberId(1L)
			.build();

		// then
		assertThat(stockHistory.getStock()).isEqualTo(stock);
		assertThat(stockHistory.getQuantityChange()).isEqualTo(-3);
		assertThat(stockHistory.getReason()).isEqualTo(StockAdjustmentReason.DISPOSAL);
		assertThat(stockHistory.getAdminMemberId()).isEqualTo(1L);
	}

	@DisplayName("변경 수량이 0인 재고 이력을 생성한다")
	@Test
	void create_whenQuantityChangeZero_createStockHistory() {
		// given
		Stock stock = createStock();

		// when
		StockHistory stockHistory = StockHistory.builder()
			.stock(stock)
			.quantityChange(0)
			.reason(StockAdjustmentReason.ADMIN_ADJUSTMENT)
			.adminMemberId(1L)
			.build();

		// then
		assertThat(stockHistory.getStock()).isEqualTo(stock);
		assertThat(stockHistory.getQuantityChange()).isZero();
		assertThat(stockHistory.getReason()).isEqualTo(StockAdjustmentReason.ADMIN_ADJUSTMENT);
		assertThat(stockHistory.getAdminMemberId()).isEqualTo(1L);
	}

	private Stock createStock() {
		return Stock.builder()
			.quantity(10)
			.build();
	}

}
