package com.commerce.order.application.port;

import java.time.Duration;

/**
 * 밖에서 온 멱등키를 선점해 같은 요청이 동시에 두 번 들어오는 것을 막는다. DB 유일 제약은 안전망으로
 * 남고, 정상 흐름의 방어는 이 층이 한다.
 *
 * <p>선점 키의 범위가 주문 생성과 주문 취소에서 다르다. 생성 시점에는 주문이 아직 없어 회원으로 묶고,
 * 취소는 언제나 주문 하나를 취소하는 것이라 주문으로 묶는다. 범위가 다르므로 자리도 따로 둔다 —
 * 한 자리에 담으면 회원 식별자와 주문 식별자가 같은 값일 때 서로의 키를 막는다.
 */
public interface OrderIdempotencyStore {

	boolean reserve(Long memberId, String idempotencyKey, Duration ttl);

	void clear(Long memberId, String idempotencyKey);

	/**
	 * 주문 취소 요청을 선점한다. 회원을 키에 넣지 않는다 — 바로 뒤에 오는 주문 조회가 소유를 확인하므로
	 * 회원을 더해도 막는 것이 늘지 않는다. 선점은 그 확인보다 먼저 돌아 그 시점에는 그 주문이 요청자의
	 * 것인지 아직 모른다.
	 */
	boolean reserveCancel(Long orderId, String idempotencyKey, Duration ttl);

	void clearCancel(Long orderId, String idempotencyKey);
}
