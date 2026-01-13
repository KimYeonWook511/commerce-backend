package com.commerce.order.service.request;

import java.util.List;

import lombok.Builder;
import lombok.Getter;

@Getter
public class OrderCreateServiceRequest {

	private Long memberId;
	private List<OrderCreateItem> items;

	@Builder
	private OrderCreateServiceRequest(Long memberId, List<OrderCreateItem> items) {
		this.memberId = memberId;
		this.items = items;
	}
}
