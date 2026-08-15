package com.commerce.payment.domain.policy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 회차마다 벌어지는 다시 집는 간격표. 결제와 환불이 같은 표를 쓰고 계산도 같아 이 자리 하나에 둔다 —
 * 두 정책이 각자 계산하면 한쪽만 바뀌어 회차 경계가 갈린다.
 *
 * <p>간격을 저장하지 않고 읽을 때 계산한다. 다음에 읽을 시각을 미리 저장하면 값을 바꿔도 이미 쌓인 행에
 * 반영되지 않는다.
 */
public class ReconcileSchedule {

	private final List<Duration> intervals;

	public ReconcileSchedule(List<Duration> intervals) {
		if (intervals.isEmpty()) {
			// 간격표가 비면 어느 회차도 대상이 되지 않아 회수가 통째로 멈춘다.
			throw new IllegalArgumentException("다시 집는 간격표가 비어 있다");
		}
		this.intervals = List.copyOf(intervals);
	}

	/**
	 * 회차별 대사 대상 조건. 회차마다 간격이 벌어지므로 하나의 임계 시각으로는 고를 수 없고, 표의 항목
	 * 수만큼 창이 나온다.
	 *
	 * <p>마지막 창은 집은 횟수의 상한을 열어 둔다. 표를 다 쓴 건도 계속 대상이어야 회수가 멈추지 않는다.
	 */
	public List<ReconcileWindow> windows(LocalDateTime at) {
		List<ReconcileWindow> windows = new ArrayList<>(intervals.size());
		int lastRound = intervals.size() - 1;
		for (int round = 0; round <= lastRound; round++) {
			int maxReconcileCount = round == lastRound ? Integer.MAX_VALUE : round;
			windows.add(new ReconcileWindow(round, maxReconcileCount, at.minus(intervals.get(round))));
		}
		return windows;
	}
}
