package com.commerce.stock.application.result;

import java.util.Map;

import lombok.Builder;
import lombok.Getter;

@Getter
public class StockDecreaseBatchResult {

	private int itemCount;
	private int totalQuantity;

	@Builder
	private StockDecreaseBatchResult(int itemCount, int totalQuantity) {
		this.itemCount = itemCount;
		this.totalQuantity = totalQuantity;
	}

	public static StockDecreaseBatchResult from(Map<Long, Integer> quantitiesByProductId) {
		int totalQuantity = quantitiesByProductId.values().stream()
			.mapToInt(Integer::intValue)
			.sum();

		return StockDecreaseBatchResult.builder()
			.itemCount(quantitiesByProductId.size())
			.totalQuantity(totalQuantity)
			.build();
	}
}
