package com.commerce.stock.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StockHistoryTest {

	@DisplayName("양수 변경 수량으로 재고 이력을 생성한다")
	@Test
	void create_whenQuantityChangePositive_createStockHistory() {
		// when
		StockHistory stockHistory = StockHistory.create(1L, 10, StockAdjustmentReason.INBOUND, 1L);

		// then
		assertThat(stockHistory.getStockId()).isEqualTo(1L);
		assertThat(stockHistory.getQuantityChange()).isEqualTo(10);
		assertThat(stockHistory.getReason()).isEqualTo(StockAdjustmentReason.INBOUND);
		assertThat(stockHistory.getAdminMemberId()).isEqualTo(1L);
	}

	@DisplayName("음수 변경 수량으로 재고 이력을 생성한다")
	@Test
	void create_whenQuantityChangeNegative_createStockHistory() {
		// when
		StockHistory stockHistory = StockHistory.create(1L, -3, StockAdjustmentReason.DISPOSAL, 1L);

		// then
		assertThat(stockHistory.getStockId()).isEqualTo(1L);
		assertThat(stockHistory.getQuantityChange()).isEqualTo(-3);
		assertThat(stockHistory.getReason()).isEqualTo(StockAdjustmentReason.DISPOSAL);
		assertThat(stockHistory.getAdminMemberId()).isEqualTo(1L);
	}

	@DisplayName("변경 수량이 0인 재고 이력을 생성한다")
	@Test
	void create_whenQuantityChangeZero_createStockHistory() {
		// when
		StockHistory stockHistory = StockHistory.create(1L, 0, StockAdjustmentReason.ADMIN_ADJUSTMENT, 1L);

		// then
		assertThat(stockHistory.getStockId()).isEqualTo(1L);
		assertThat(stockHistory.getQuantityChange()).isZero();
		assertThat(stockHistory.getReason()).isEqualTo(StockAdjustmentReason.ADMIN_ADJUSTMENT);
		assertThat(stockHistory.getAdminMemberId()).isEqualTo(1L);
	}

}
