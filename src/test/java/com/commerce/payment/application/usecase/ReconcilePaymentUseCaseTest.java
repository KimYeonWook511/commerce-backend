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
import com.commerce.payment.application.service.PaymentService;
import com.commerce.payment.application.service.PgCallLogService;
import com.commerce.payment.domain.policy.PaymentPostProcessPolicy;
import com.commerce.payment.domain.policy.ReconcileWindow;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.repository.ReconcileTarget;

/**
 * 한 회차가 대상을 개수로 자르지 않고 다 처리하되 밀리면 알리는지 확인한다. 대상 수를 세는 자리와
 * 알리는 자리가 이 유스케이스 안에만 있어, 집기부터는 물러나게 두고 그 두 가지만 본다.
 */
@ExtendWith(MockitoExtension.class)
class ReconcilePaymentUseCaseTest {

	private static final int THRESHOLD = 2;

	@Mock
	private PaymentRepository paymentRepository;
	@Mock
	private PaymentGatewayPort paymentGatewayPort;
	@Mock
	private PaymentService paymentService;
	@Mock
	private PgCallLogService pgCallLogService;
	@Mock
	private ConfirmApprovalUseCase confirmApprovalUseCase;
	@Mock
	private NotificationPort notificationPort;
	@Mock
	private PaymentPostProcessPolicy policy;

	@InjectMocks
	private ReconcilePaymentUseCase reconcilePaymentUseCase;

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

		reconcilePaymentUseCase.reconcile();

		then(notificationPort).should().notifyReconcileBacklog(any(), eq(THRESHOLD + 1), eq(THRESHOLD));
		then(paymentService).should(times(THRESHOLD + 1)).recordReconciled(anyLong(), anyInt(), any());
	}

	@DisplayName("대상이 임계 이하면 알리지 않는다")
	@Test
	void reconcile_whenTargetsWithinThreshold_doesNotAlert() {
		givenTargets(THRESHOLD);
		givenEveryClaimYields();
		given(policy.isReconcileBacklogged(THRESHOLD)).willReturn(false);

		reconcilePaymentUseCase.reconcile();

		then(notificationPort).should(never()).notifyReconcileBacklog(any(), anyInt(), anyInt());
		then(paymentService).should(times(THRESHOLD)).recordReconciled(anyLong(), anyInt(), any());
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
		assertThatCode(() -> reconcilePaymentUseCase.reconcile()).doesNotThrowAnyException();
		then(paymentService).should(times(THRESHOLD + 1)).recordReconciled(anyLong(), anyInt(), any());
	}

	@DisplayName("대상이 없으면 알리지도 집지도 않는다")
	@Test
	void reconcile_whenNoTargets_doesNothing() {
		givenTargets(0);

		reconcilePaymentUseCase.reconcile();

		then(notificationPort).shouldHaveNoInteractions();
		then(paymentService).shouldHaveNoInteractions();
	}

	private void givenTargets(int count) {
		List<ReconcileTarget> targets = IntStream.range(0, count)
			.mapToObj(index -> new ReconcileTarget((long) index + 1, 0))
			.toList();
		given(paymentRepository.findUnknownReconcileTargets(anyInt(), anyInt(), any())).willReturn(targets);
		given(paymentRepository.findInProgressReconcileTargets(any(), anyInt(), anyInt(), any()))
			.willReturn(List.of());
	}

	/** 집기에서 물러나게 두어 결제사 호출 없이 대상 수만 센다 */
	private void givenEveryClaimYields() {
		given(paymentService.recordReconciled(anyLong(), anyInt(), any())).willReturn(Optional.empty());
	}
}
