package com.commerce.order.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OrderStatus {

	INIT("주문생성"),
	CANCELED("주문취소"),
	RECEIVED("주문접수"),
	COMPLETED("처리완료");

	private final String text;

}
