package com.commerce.order.presentation.http.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
public class OrderCancelRequest {

	/** 없거나 비어 있으면 아직 남은 품목의 잔여수량 전부를 취소한다 */
	@Valid
	private List<@NotNull(message = "취소 품목은 비어 있을 수 없습니다") OrderCancelItemRequest> items;
}
