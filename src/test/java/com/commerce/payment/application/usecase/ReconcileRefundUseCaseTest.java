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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.commerce.payment.application.port.NotificationPort;
import com.commerce.payment.application.port.PaymentGatewayPort;
import com.commerce.payment.application.service.RefundService;
import com.commerce.payment.domain.policy.RefundPostProcessPolicy;
import com.commerce.payment.domain.policy.ReconcileWindow;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.repository.ReconcileTarget;
import com.commerce.payment.domain.repository.RefundRepository;

/**
 * 한 회차가 대상을 개수로 자르지 않고 다 처리하되 밀리면 알리는지 확인한다. 대상 수를 세는 자리와
 * 알리는 자리가 이 유스케이스 안에만 있어, 집기부터는 물러나게 두고 그 두 가지만 본다.
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
	@Mock
	private RefundPostProcessPolicy policy;

	@InjectMocks
	private ReconcileRefundUseCase reconcileRefundUseCase;

	@BeforeEach
	void setUp() {
		given(policy.reconcileWindows(any())).willReturn(
			List.of(new ReconcileWindow(0, Integer.MAX_VALUE, LocalDateTime.now())));
	}

	@DisplayName("대상이 임계를 넘으면 자르지 않고 다 처리하되 밀렸다고 알린다")
	@Test
	void reconcile_whenTargetsExceedThreshold_processesAllAndAlerts() {
		givenTargets(THRESHOLD + 1);
		givenEveryClaimYields();
		given(policy.isReconcileBacklogged(THRESHOLD + 1)).willReturn(true);
		given(policy.reconcileBacklogThreshold()).willReturn(THRESHOLD);

		reconcileRefundUseCase.reconcile();

		then(notificationPort).should().notifyReconcileBacklog(any(), eq(THRESHOLD + 1), eq(THRESHOLD));
		then(refundService).should(times(THRESHOLD + 1)).recordReconciled(anyLong(), anyInt(), any());
	}

	@DisplayName("한 번 알린 뒤 통지 간격 안에 다시 밀려도 또 알리지 않는다")
	@Test
	void reconcile_whenAlertedWithinInterval_doesNotAlertAgain() {
		givenTargets(THRESHOLD + 1);
		givenEveryClaimYields();
		given(policy.isReconcileBacklogged(THRESHOLD + 1)).willReturn(true);
		given(policy.reconcileBacklogThreshold()).willReturn(THRESHOLD);
		// 방금 알린 것으로 보이게 임계 시각을 과거로 둔다.
		given(policy.notifiedBefore(any())).willReturn(LocalDateTime.now().minusHours(1));

		reconcileRefundUseCase.reconcile();
		reconcileRefundUseCase.reconcile();

		then(notificationPort).should(times(1)).notifyReconcileBacklog(any(), anyInt(), anyInt());
	}

	@DisplayName("통지 간격이 지나면 밀린 상태가 이어져도 다시 알린다")
	@Test
	void reconcile_whenAlertIntervalPassed_alertsAgain() {
		givenTargets(THRESHOLD + 1);
		givenEveryClaimYields();
		given(policy.isReconcileBacklogged(THRESHOLD + 1)).willReturn(true);
		given(policy.reconcileBacklogThreshold()).willReturn(THRESHOLD);
		// 마지막 알림이 임계보다 앞선 것으로 보이게 임계 시각을 미래로 둔다.
		given(policy.notifiedBefore(any())).willReturn(LocalDateTime.now().plusHours(1));

		reconcileRefundUseCase.reconcile();
		reconcileRefundUseCase.reconcile();

		then(notificationPort).should(times(2)).notifyReconcileBacklog(any(), anyInt(), anyInt());
	}

	@DisplayName("알림이 실패하면 보낸 것으로 세지 않아 다음 주기가 다시 시도한다")
	@Test
	void reconcile_whenAlertFails_retriesNextRound() {
		givenTargets(THRESHOLD + 1);
		givenEveryClaimYields();
		given(policy.isReconcileBacklogged(THRESHOLD + 1)).willReturn(true);
		given(policy.reconcileBacklogThreshold()).willReturn(THRESHOLD);
		willThrow(new IllegalStateException("통지 실패"))
			.given(notificationPort).notifyReconcileBacklog(any(), anyInt(), anyInt());

		reconcileRefundUseCase.reconcile();
		reconcileRefundUseCase.reconcile();

		// 못 보낸 것을 보낸 것으로 세면 밀린 채로 조용해진다.
		then(notificationPort).should(times(2)).notifyReconcileBacklog(any(), anyInt(), anyInt());
	}

	@DisplayName("대상이 임계 이하면 알리지 않는다")
	@Test
	void reconcile_whenTargetsWithinThreshold_doesNotAlert() {
		givenTargets(THRESHOLD);
		givenEveryClaimYields();
		given(policy.isReconcileBacklogged(THRESHOLD)).willReturn(false);

		reconcileRefundUseCase.reconcile();

		then(notificationPort).should(never()).notifyReconcileBacklog(any(), anyInt(), anyInt());
		then(refundService).should(times(THRESHOLD)).recordReconciled(anyLong(), anyInt(), any());
	}

	@DisplayName("밀렸다는 알림이 실패해도 그 회차의 대상이 다 처리된다")
	@Test
	void reconcile_whenBacklogAlertFails_stillProcessesEveryTarget() {
		givenTargets(THRESHOLD + 1);
		givenEveryClaimYields();
		given(policy.isReconcileBacklogged(THRESHOLD + 1)).willReturn(true);
		given(policy.reconcileBacklogThreshold()).willReturn(THRESHOLD);
		willThrow(new IllegalStateException("통지 실패"))
			.given(notificationPort).notifyReconcileBacklog(any(), anyInt(), anyInt());

		// 전파하면 밀렸을 때 알리려고 둔 것이 밀렸을 때 회수를 통째로 멈춘다.
		assertThatCode(() -> reconcileRefundUseCase.reconcile()).doesNotThrowAnyException();
		then(refundService).should(times(THRESHOLD + 1)).recordReconciled(anyLong(), anyInt(), any());
	}

	@DisplayName("대상이 없으면 알리지도 집지도 않는다")
	@Test
	void reconcile_whenNoTargets_doesNothing() {
		givenTargets(0);

		reconcileRefundUseCase.reconcile();

		then(notificationPort).shouldHaveNoInteractions();
		then(refundService).shouldHaveNoInteractions();
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
