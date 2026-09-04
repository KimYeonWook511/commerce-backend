package com.commerce.payment.domain.repository;

/**
 * 대사가 이번 회차에 집을 대상. 선점에 필요한 값만 담는다 — 한 회차의 대상 수에 상한이 없어, 행을
 * 통째로 읽으면 밀렸을 때 쓰지도 않을 필드를 대상 수만큼 싣는다.
 *
 * <p>회차를 함께 싣는 것은 집는 자리가 그 값을 다시 확인하기 때문이다. 고른 뒤 집기까지 사이에 다른
 * 주기가 같은 건을 집어 갔는지는 그 값으로만 갈린다.
 *
 * <p>결제와 환불이 같은 모양이라 하나를 함께 쓴다.
 *
 * @param id             집을 행의 식별자
 * @param reconcileCount 고를 때 본 회차
 */
public record ReconcileTarget(Long id, int reconcileCount) {
}
