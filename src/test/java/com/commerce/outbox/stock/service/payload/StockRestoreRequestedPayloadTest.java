package com.commerce.outbox.stock.service.payload;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StockRestoreRequestedPayloadTest {

	@DisplayName("아이템 목록이 유효하면 true를 반환한다")
	@Test
	void hasValidItems_whenItemsAreValid_returnTrue() {
		// given
		StockRestoreRequestedPayload payload = StockRestoreRequestedPayload.builder()
			.orderId(1L)
			.items(List.of(
				StockRestoreRequestedPayload.Item.builder().productId(10L).quantity(2).build(),
				StockRestoreRequestedPayload.Item.builder().productId(20L).quantity(3).build()
			))
			.build();

		// when
		boolean result = payload.hasValidItems();

		// then
		assertThat(result).isTrue();
	}

	@DisplayName("아이템 목록이 비어있으면 false를 반환한다")
	@Test
	void hasValidItems_whenItemsAreEmpty_returnFalse() {
		// given
		StockRestoreRequestedPayload payload = StockRestoreRequestedPayload.builder()
			.orderId(1L)
			.items(List.of())
			.build();

		// when
		boolean result = payload.hasValidItems();

		// then
		assertThat(result).isFalse();
	}

	@DisplayName("아이템에 상품 ID가 없거나 수량이 0 이하이면 false를 반환한다")
	@Test
	void hasValidItems_whenItemContainsInvalidValue_returnFalse() {
		// given
		StockRestoreRequestedPayload payload = StockRestoreRequestedPayload.builder()
			.orderId(1L)
			.items(List.of(
				StockRestoreRequestedPayload.Item.builder().productId(null).quantity(1).build(),
				StockRestoreRequestedPayload.Item.builder().productId(20L).quantity(0).build()
			))
			.build();

		// when
		boolean result = payload.hasValidItems();

		// then
		assertThat(result).isFalse();
	}
}
