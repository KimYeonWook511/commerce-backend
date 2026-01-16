package com.commerce.stock.service.request;

import java.util.Map;

import lombok.Builder;
import lombok.Getter;

@Getter
public class StockDecreaseBatchServiceRequest {

	private Map<Long, Integer> quantitiesByProductId;

	@Builder
	private StockDecreaseBatchServiceRequest(Map<Long, Integer> quantitiesByProductId) {
		this.quantitiesByProductId = quantitiesByProductId;
	}

	public static StockDecreaseBatchServiceRequest from(Map<Long, Integer> quantitiesByProductId) {
		return StockDecreaseBatchServiceRequest.builder()
			.quantitiesByProductId(quantitiesByProductId)
			.build();
	}
}
