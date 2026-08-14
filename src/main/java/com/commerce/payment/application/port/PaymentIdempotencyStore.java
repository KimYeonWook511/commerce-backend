package com.commerce.payment.application.port;

import java.time.Duration;

/**
 * 결제 시작 요청 멱등키를 처리 중인 것으로 선점한다.
 *
 * <p>같은 값이 동시에 닿았을 때 하나만 통과시키는 자리다. DB 유일 제약은 앞 요청이 커밋된 뒤에야
 * 걸리므로, 커밋 전에 도착한 두 번째 요청을 걸러내려면 이 층이 필요하다.
 *
 * <p>주문 도메인에 같은 성질의 것이 있지만 그것을 주입받지 않는다 — 결제가 주문의 인터페이스를
 * 의존하면 이번에 세운 도메인 경계가 깨진다. 인터페이스는 쓰는 만큼만 둘이다.
 */
public interface PaymentIdempotencyStore {

	boolean reserve(Long memberId, String idempotencyKey, Duration ttl);

	void clear(Long memberId, String idempotencyKey);
}
