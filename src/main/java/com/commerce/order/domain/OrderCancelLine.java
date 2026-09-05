package com.commerce.order.domain;

/**
 * 취소 요청 한 줄 — 어느 주문 품목을 몇 개. 주문 도메인의 관문이 응용 계층의 요청 타입을 받으면
 * 의존 방향이 뒤집히므로, 그 자리를 이 작은 값 타입이 맡는다.
 */
public record OrderCancelLine(Long orderItemId, int quantity) {
}
