package com.commerce.stock.application.command;

import java.util.Map;

import lombok.Builder;
import lombok.Getter;

@Getter
public class StockDecreaseBatchCommand {

	private Map<Long, Integer> quantitiesByProductId;

	@Builder
	private StockDecreaseBatchCommand(Map<Long, Integer> quantitiesByProductId) {
		this.quantitiesByProductId = quantitiesByProductId;
	}

	public static StockDecreaseBatchCommand from(Map<Long, Integer> quantitiesByProductId) {
		return StockDecreaseBatchCommand.builder()
			.quantitiesByProductId(quantitiesByProductId)
			.build();
	}
}
