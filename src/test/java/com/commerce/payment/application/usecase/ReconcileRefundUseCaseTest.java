package com.commerce.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.commerce.payment.application.port.NotificationPort;
import com.commerce.payment.application.port.PaymentGatewayPort;
import com.commerce.payment.application.service.RefundService;
import com.commerce.payment.domain.policy.RefundPostProcessPolicy;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.repository.RefundRepository;
import com.commerce.payment.domain.repository.ReconcileTarget;

/**
 * 한 회차가 대상을 개수로 자르지 않고 다 처리하는지, 그리고 밀렸을 때 알리는 판정이 맞는지 본다.
 * 집기부터는 물러나게 두어 결제사 호출 없이 그 두 가지만 남긴다.
 *
 * <p>정책은 대역이 아니라 진짜를 쓴다. 밀림 판정과 재알림 간격이 이 유스케이스의 분기를 정하므로,
 * 대역으로 답을 정해 주면 그 경계가 검증되지 않은 채 통과한다. 회차 간격표는 창이 하나만 나오게 두어
 * 조회가 한 벌만 돌게 한다.
 */
@ExtendWith(MockitoExtension.class)
class ReconcileRefundUseCaseTest {

	private static final int THRESHOLD = 2;

	@Mock
	private RefundRepository refundRepository;
	@Mock
	private PaymentRepository paymentRepository;
	@Mock
	private PaymentGatewayPort paymentGatewayPort;
	@Mock
	private RefundService refundService;
	@Mock
	private ExecuteRefundUseCase executeRefundUseCase;
	@Mock
	private NotificationPort notificationPort;

	@DisplayName("대상이 임계를 넘으면 밀렸다고 알린다")
	@Test
	void reconcile_whenTargetsExceedThreshold_alerts() {
		ReconcileRefundUseCase useCase = useCaseWith(Duration.ofHours(1));
		givenTargets(THRESHOLD + 1);
		givenEveryClaimYields();

		useCase.reconcile();

		then(notificationPort).should().notifyReconcileBacklog(any(), eq(THRESHOLD + 1), eq(THRESHOLD));
	}

	@DisplayName("대상이 임계와 같으면 알리지 않는다 — 넘어야 밀린 것이다")
	@Test
	void reconcile_whenTargetsAtThreshold_doesNotAlert() {
		ReconcileRefundUseCase useCase = useCaseWith(Duration.ofHours(1));
		givenTargets(THRESHOLD);
		givenEveryClaimYields();

		useCase.reconcile();

		then(notificationPort).should(never()).notifyReconcileBacklog(any(), anyInt(), anyInt());
	}

	@DisplayName("한 번 알린 뒤 통지 간격 안에 다시 밀려도 또 알리지 않는다")
	@Test
	void reconcile_whenAlertedWithinInterval_doesNotAlertAgain() {
		ReconcileRefundUseCase useCase = useCaseWith(Duration.ofHours(1));
		givenTargets(THRESHOLD + 1);
		givenEveryClaimYields();

		useCase.reconcile();
		useCase.reconcile();

		then(notificationPort).should(times(1)).notifyReconcileBacklog(any(), anyInt(), anyInt());
	}

	@DisplayName("통지 간격이 지나면 밀린 상태가 이어져도 다시 알린다")
	@Test
	void reconcile_whenAlertIntervalPassed_alertsAgain() {
		ReconcileRefundUseCase useCase = useCaseWith(Duration.ZERO);
		givenTargets(THRESHOLD + 1);
		givenEveryClaimYields();

		useCase.reconcile();
		useCase.reconcile();

		then(notificationPort).should(times(2)).notifyReconcileBacklog(any(), anyInt(), anyInt());
	}

	@DisplayName("알림이 실패해도 그 회차의 대상을 끝까지 집는다")
	@Test
	void reconcile_whenBacklogAlertFails_stillClaimsEveryTarget() {
		ReconcileRefundUseCase useCase = useCaseWith(Duration.ofHours(1));
		givenTargets(THRESHOLD + 1);
		givenEveryClaimYields();
		willThrow(new IllegalStateException("통지 실패"))
			.given(notificationPort).notifyReconcileBacklog(any(), anyInt(), anyInt());

		// 전파하면 밀렸을 때 알리려고 둔 것이 밀렸을 때 회수를 통째로 멈춘다.
		assertThatCode(useCase::reconcile).doesNotThrowAnyException();
		then(refundService).should(times(THRESHOLD + 1)).recordReconciled(anyLong(), anyInt(), any());
	}

	@DisplayName("알림이 실패하면 보낸 것으로 세지 않아 다음 회차가 다시 시도한다")
	@Test
	void reconcile_whenAlertFails_retriesNextRound() {
		ReconcileRefundUseCase useCase = useCaseWith(Duration.ofHours(1));
		givenTargets(THRESHOLD + 1);
		givenEveryClaimYields();
		willThrow(new IllegalStateException("통지 실패"))
			.given(notificationPort).notifyReconcileBacklog(any(), anyInt(), anyInt());

		useCase.reconcile();
		useCase.reconcile();

		// 못 보낸 것을 보낸 것으로 세면 밀린 채로 조용해진다.
		then(notificationPort).should(times(2)).notifyReconcileBacklog(any(), anyInt(), anyInt());
	}

	@DisplayName("대상이 없으면 알리지도 집지도 않는다")
	@Test
	void reconcile_whenNoTargets_doesNothing() {
		ReconcileRefundUseCase useCase = useCaseWith(Duration.ofHours(1));
		givenTargets(0);

		useCase.reconcile();

		then(notificationPort).shouldHaveNoInteractions();
		then(refundService).shouldHaveNoInteractions();
	}

	/** 창이 하나만 나오도록 간격표를 한 칸으로 둔다 — 조회가 한 벌만 돌아 대상 수를 그대로 센다 */
	private ReconcileRefundUseCase useCaseWith(Duration notifyInterval) {
		RefundPostProcessPolicy policy = new RefundPostProcessPolicy(
			Duration.ofSeconds(90), List.of(Duration.ofSeconds(10)), Duration.ofHours(1),
			notifyInterval, THRESHOLD);
		return new ReconcileRefundUseCase(refundRepository, paymentRepository, paymentGatewayPort,
			refundService, executeRefundUseCase, notificationPort, policy);
	}

	private void givenTargets(int count) {
		List<ReconcileTarget> targets = IntStream.range(0, count)
			.mapToObj(index -> new ReconcileTarget((long) index + 1, 0))
			.toList();
		given(refundRepository.findUnknownReconcileTargets(anyInt(), anyInt(), any())).willReturn(targets);
		given(refundRepository.findInProgressReconcileTargets(any(), anyInt(), anyInt(), any()))
			.willReturn(List.of());
	}

	/** 집기에서 물러나게 두어 결제사 호출 없이 대상 수만 센다 */
	private void givenEveryClaimYields() {
		given(refundService.recordReconciled(anyLong(), anyInt(), any())).willReturn(Optional.empty());
	}
}
