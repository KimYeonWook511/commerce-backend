package com.commerce.stock.service.response;

import java.util.Map;

import lombok.Builder;
import lombok.Getter;

@Getter
public class StockDecreaseBatchResponse {

	private int itemCount;
	private int totalQuantity;

	@Builder
	private StockDecreaseBatchResponse(int itemCount, int totalQuantity) {
		this.itemCount = itemCount;
		this.totalQuantity = totalQuantity;
	}

	public static StockDecreaseBatchResponse from(Map<Long, Integer> quantitiesByProductId) {
		int totalQuantity = quantitiesByProductId.values().stream()
			.mapToInt(Integer::intValue)
			.sum();

		return StockDecreaseBatchResponse.builder()
			.itemCount(quantitiesByProductId.size())
			.totalQuantity(totalQuantity)
			.build();
	}
}
