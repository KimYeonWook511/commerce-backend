package com.commerce.payment.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefundPostProcessPolicyTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 12, 0);

	private static final List<Duration> INTERVALS = List.of(
		Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(2));

	private final RefundPostProcessPolicy policy = new RefundPostProcessPolicy(
		Duration.ofSeconds(90), INTERVALS, Duration.ofHours(1), Duration.ofHours(1));

	@DisplayName("대사 유예와 통지 승급·간격을 시각으로 바꿔 준다")
	@Test
	void thresholds_whenAsked_areDerivedFromTheGivenTime() {
		assertThat(policy.requestedBefore(NOW)).isEqualTo(NOW.minusSeconds(90));
		assertThat(policy.createdBeforeForNotify(NOW)).isEqualTo(NOW.minusHours(1));
		assertThat(policy.notifiedBefore(NOW)).isEqualTo(NOW.minusHours(1));
	}

	@DisplayName("대사 유예가 결제보다 길다 — 겹쳐 전송되면 같은 취소가 두 번 나가기 때문이다")
	@Test
	void requestedBefore_whenComparedWithPayment_leavesMoreRoom() {
		PaymentPostProcessPolicy paymentPolicy = new PaymentPostProcessPolicy(
			Duration.ofSeconds(30), INTERVALS, Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1));

		assertThat(policy.requestedBefore(NOW)).isBefore(paymentPolicy.requestedBefore(NOW));
	}

	@DisplayName("다시 집는 간격표를 결제와 같은 계산으로 쓴다")
	@Test
	void reconcileWindows_whenBuilt_matchThePaymentSideSchedule() {
		PaymentPostProcessPolicy paymentPolicy = new PaymentPostProcessPolicy(
			Duration.ofSeconds(30), INTERVALS, Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1));

		assertThat(policy.reconcileWindows(NOW)).isEqualTo(paymentPolicy.reconcileWindows(NOW));
	}

	@DisplayName("마지막 창은 집은 횟수의 상한을 열어 두어 여러 번 집힌 건도 계속 대상이 된다")
	@Test
	void reconcileWindows_whenLastRound_leavesUpperBoundOpen() {
		List<ReconcileWindow> windows = policy.reconcileWindows(NOW);

		assertThat(windows).hasSize(INTERVALS.size());
		// 상한을 닫으면 여러 번 집은 건이 어느 창에도 안 들어 회수가 조용히 멈추고, 돈이 안 돌아간 채 남는다.
		assertThat(windows.get(windows.size() - 1).maxReconcileCount()).isEqualTo(Integer.MAX_VALUE);
	}

	@DisplayName("간격표가 비어 있으면 정책을 만들 수 없다")
	@Test
	void construct_whenIntervalsAreEmpty_isRejected() {
		assertThatThrownBy(() -> new RefundPostProcessPolicy(
			Duration.ofSeconds(90), List.of(), Duration.ofHours(1), Duration.ofHours(1)))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
