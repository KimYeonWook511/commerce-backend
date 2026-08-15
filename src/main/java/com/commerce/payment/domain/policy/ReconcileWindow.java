package com.commerce.payment.domain.policy;

import java.time.LocalDateTime;

/**
 * 한 회차의 대사 대상을 고르는 조건.
 *
 * <p>간격을 저장하지 않고 회차마다 임계 시각을 그때그때 계산하므로, 간격 값을 바꾸면 이미 쌓인 행에도
 * 바로 반영된다. 계산이 조회 밖에 있는 것도 같은 이유다 — 간격을 정하는 것은 정책이고, 조회는 상태와
 * 회차와 임계 시각만 받는다.
 *
 * @param minReconcileCount 집은 횟수의 하한
 * @param maxReconcileCount 집은 횟수의 상한. 마지막 회차는 그 위를 전부 받아 회수가 멈추지 않는다
 * @param reconciledBefore  이 시각보다 앞서 집은 건만 다시 집는다
 */
public record ReconcileWindow(
	int minReconcileCount,
	int maxReconcileCount,
	LocalDateTime reconciledBefore
) {
}
