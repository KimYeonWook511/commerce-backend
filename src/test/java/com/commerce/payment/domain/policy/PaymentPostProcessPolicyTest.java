package com.commerce.payment.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentPostProcessPolicyTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 12, 0);

	private static final List<Duration> INTERVALS = List.of(
		Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(2));

	private final PaymentPostProcessPolicy policy = new PaymentPostProcessPolicy(
		Duration.ofSeconds(30), INTERVALS, Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1));

	@DisplayName("다시 집는 창이 간격표의 항목 수만큼 나오고 회차마다 임계 시각이 벌어진다")
	@Test
	void reconcileWindows_whenBuilt_widensThresholdPerRound() {
		List<ReconcileWindow> windows = policy.reconcileWindows(NOW);

		assertThat(windows).hasSize(INTERVALS.size());
		assertThat(windows.get(0).reconciledBefore()).isEqualTo(NOW.minusSeconds(10));
		assertThat(windows.get(1).reconciledBefore()).isEqualTo(NOW.minusSeconds(30));
		assertThat(windows.get(2).reconciledBefore()).isEqualTo(NOW.minusMinutes(2));
	}

	@DisplayName("창마다 집은 횟수가 한 회차씩만 걸려 같은 건이 두 창에 들지 않는다")
	@Test
	void reconcileWindows_whenBuilt_assignsOneRoundPerWindow() {
		List<ReconcileWindow> windows = policy.reconcileWindows(NOW);

		assertThat(windows.get(0).minReconcileCount()).isZero();
		assertThat(windows.get(0).maxReconcileCount()).isZero();
		assertThat(windows.get(1).minReconcileCount()).isEqualTo(1);
		assertThat(windows.get(1).maxReconcileCount()).isEqualTo(1);
	}

	@DisplayName("마지막 창은 집은 횟수의 상한을 열어 두어 표를 다 쓴 건도 계속 대상이 된다")
	@Test
	void reconcileWindows_whenLastRound_leavesUpperBoundOpen() {
		List<ReconcileWindow> windows = policy.reconcileWindows(NOW);

		ReconcileWindow last = windows.get(windows.size() - 1);
		assertThat(last.minReconcileCount()).isEqualTo(INTERVALS.size() - 1);
		// 상한을 닫으면 여러 번 집은 건이 어느 창에도 안 들어 회수가 조용히 멈춘다.
		assertThat(last.maxReconcileCount()).isEqualTo(Integer.MAX_VALUE);
	}

	@DisplayName("간격표가 비어 있으면 정책을 만들 수 없다")
	@Test
	void construct_whenIntervalsAreEmpty_isRejected() {
		assertThatThrownBy(() -> new PaymentPostProcessPolicy(
			Duration.ofSeconds(30), List.of(), Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@DisplayName("대사 유예·통지 승급·통지 간격·만료 임계를 시각으로 바꿔 준다")
	@Test
	void thresholds_whenAsked_areDerivedFromTheGivenTime() {
		assertThat(policy.requestedBefore(NOW)).isEqualTo(NOW.minusSeconds(30));
		assertThat(policy.createdBeforeForNotify(NOW)).isEqualTo(NOW.minusHours(1));
		assertThat(policy.notifiedBefore(NOW)).isEqualTo(NOW.minusHours(1));
		assertThat(policy.createdBeforeForExpire(NOW)).isEqualTo(NOW.minusHours(1));
	}
}
