package com.commerce.order.application.dto;

import java.util.List;

import lombok.Builder;
import lombok.Getter;

@Getter
public class OrderCreateCommand {

	private Long memberId;
	private String idempotencyKey;
	private List<OrderCreateItem> items;

	@Builder
	private OrderCreateCommand(Long memberId, String idempotencyKey, List<OrderCreateItem> items) {
		this.memberId = memberId;
		this.idempotencyKey = idempotencyKey;
		this.items = items;
	}
}
