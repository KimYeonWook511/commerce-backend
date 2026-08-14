package com.commerce.payment.domain;

/**
 * 결제사를 무엇으로 불렀나. 이력 조회는 여기 없다 — 결제사 상태를 바꾸려는 요청이 아니라 확인이고,
 * 섞으면 몇 번 시도했는지가 조회 횟수에 묻힌다.
 */
public enum PgCallType {
	APPROVE,
	REFUND
}
