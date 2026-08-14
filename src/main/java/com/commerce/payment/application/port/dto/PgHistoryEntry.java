package com.commerce.payment.application.port.dto;

import java.time.LocalDateTime;

/**
 * 이력 항목 하나를 우리 값으로 옮긴 것.
 *
 * <p>어댑터는 결제사가 돌려준 항목을 걸러 내지 않는다. 우리 키가 실린 것만 넘기면 우리가 모르는
 * 환불이 경계에서 사라지는데, 그 항목이 있다는 사실 자체가 우리 기록과 결제사 기록이 갈렸다는 유일한
 * 신호다. 어느 항목이 우리 것인지 가르는 판정은 도메인이 한다.
 *
 * @param type            원결제인가 환불인가
 * @param succeeded       그 시도가 성공했나. 실패한 시도도 한 줄로 남으므로 존재만으로 완료를 단정하지 않는다
 * @param amount          그 항목의 금액
 * @param occurredAt      결제사가 남긴 처리 시각. 형식을 읽을 수 없으면 비어 있다
 * @param paymentKey      항목에 실린 우리 결제 키. 원결제 항목이 그 결제의 것인지 가르는 값이다
 * @param refundAttemptKey 항목에 실린 우리 환불 시도 키. 결제사 화면에서 직접 취소한 건에는 없다
 * @param pgTransactionId 결제사가 발급한 거래 번호
 */
public record PgHistoryEntry(
	PgHistoryEntryType type,
	boolean succeeded,
	int amount,
	LocalDateTime occurredAt,
	String paymentKey,
	String refundAttemptKey,
	String pgTransactionId
) {
}
