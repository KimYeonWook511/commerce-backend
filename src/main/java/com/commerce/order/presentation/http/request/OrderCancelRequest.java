package com.commerce.order.presentation.http.request;

import java.util.List;

import jakarta.validation.Valid;
import lombok.Getter;

@Getter
public class OrderCancelRequest {

	/** 없거나 비어 있으면 아직 남은 품목의 잔여수량 전부를 취소한다 */
	@Valid
	private List<OrderCancelItemRequest> items;
}
