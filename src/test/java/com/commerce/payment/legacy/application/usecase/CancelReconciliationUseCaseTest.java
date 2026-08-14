package com.commerce.payment.legacy.application.usecase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.payment.legacy.application.port.NotificationPort;
import com.commerce.payment.legacy.application.service.DelayPaymentReconcileService;
import com.commerce.payment.legacy.application.service.EscalateApprovePaymentService;
import com.commerce.payment.legacy.application.service.EscalateCancelPaymentService;
import com.commerce.payment.legacy.application.service.FailApprovePaymentService;
import com.commerce.payment.legacy.application.service.FailCancelPaymentService;
import com.commerce.payment.legacy.application.service.MarkUnknownCancelPaymentService;
import com.commerce.payment.legacy.application.service.SucceedCancelPaymentService;
import com.commerce.payment.legacy.domain.Payment;
import com.commerce.payment.legacy.domain.PaymentFailCode;
import com.commerce.payment.legacy.domain.PaymentProvider;
import com.commerce.payment.legacy.domain.PaymentReservation;
import com.commerce.payment.legacy.domain.PaymentStatus;
import com.commerce.payment.legacy.domain.PaymentType;
import com.commerce.payment.legacy.domain.exception.PaymentErrorCode;
import com.commerce.payment.legacy.domain.exception.PaymentException;
import com.commerce.payment.legacy.domain.repository.PaymentRepository;
import com.commerce.payment.legacy.naverpay.application.port.NaverPayGateway;
import com.commerce.payment.legacy.naverpay.application.port.result.NaverPayCancelResult;
import com.commerce.payment.legacy.naverpay.application.port.result.NaverPayHistoryResult;
import com.commerce.payment.legacy.postprocess.flow.PaymentPostProcessFlowPolicy;
import com.commerce.payment.legacy.postprocess.target.PaymentPostProcessTargetPolicy;

@ExtendWith(MockitoExtension.class)
class CancelReconciliationUseCaseTest {

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private DelayPaymentReconcileService delayPaymentReconcileService;

	@Mock
	private FailApprovePaymentService failApprovePaymentService;

	@Mock
	private EscalateApprovePaymentService escalateApprovePaymentService;

	@Mock
	private EscalateCancelPaymentService escalateCancelPaymentService;

	@Mock
	private SucceedCancelPaymentService succeedCancelPaymentService;

	@Mock
	private FailCancelPaymentService failCancelPaymentService;

	@Mock
	private MarkUnknownCancelPaymentService markUnknownCancelPaymentService;

	@Mock
	private ConfirmApprovalUseCase confirmApprovalUseCase;

	@Mock
	private NaverPayGateway naverPayGateway;

	@Mock
	private NotificationPort notificationPort;

	@InjectMocks
	private ReconcilePaymentUseCase reconcilePaymentUseCase;

	private final PaymentPostProcessTargetPolicy realTargetPolicy = new PaymentPostProcessTargetPolicy();
	private final PaymentPostProcessFlowPolicy realFlowPolicy = new PaymentPostProcessFlowPolicy();

	private void injectPolicies() {
		ReflectionTestUtils.setField(reconcilePaymentUseCase, "targetPolicy", realTargetPolicy);
		ReflectionTestUtils.setField(reconcilePaymentUseCase, "flowPolicy", realFlowPolicy);
	}

	// --- CANCEL_RECONCILE: PG_CANCELED(ALREADY_CANCELED) → CANCEL SUCCEEDED ---

	@DisplayName("stale CANCEL UNKNOWN 결제를 대사할 때 PG가 취소 확인되면 succeedCancelPaymentService를 호출한다")
	@Test
	void reconcileCancelUnknown_pgCanceled_callsSucceed() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment = unknownCancelPayment("PAY-C-1", "pg-c-1", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of());
		given(paymentRepository.findStaleCancelPaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(cancelPayment));
		given(naverPayGateway.getApprovalHistory("pg-c-1"))
			.willReturn(NaverPayHistoryResult.canceled());

		reconcilePaymentUseCase.reconcile();

		then(succeedCancelPaymentService).should().succeed(
			eq("PAY-C-1"), eq(PaymentProvider.NAVERPAY), eq("pg-c-1"), any(LocalDateTime.class));
	}

	@DisplayName("stale CANCEL REQUESTED 결제를 대사할 때 PG가 취소 확인되면 succeedCancelPaymentService를 호출한다")
	@Test
	void reconcileCancelRequested_pgCanceled_callsSucceed() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment = requestedCancelPayment("PAY-C-2", "pg-c-2", now.minusMinutes(20));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of());
		given(paymentRepository.findStaleCancelPaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(cancelPayment));
		given(naverPayGateway.getApprovalHistory("pg-c-2"))
			.willReturn(NaverPayHistoryResult.canceled());

		reconcilePaymentUseCase.reconcile();

		then(succeedCancelPaymentService).should().succeed(
			eq("PAY-C-2"), eq(PaymentProvider.NAVERPAY), eq("pg-c-2"), any(LocalDateTime.class));
	}

	// --- CANCEL_RECONCILE: PG_APPROVED(CANCEL_RETRY) → 취소 재시도 ---

	@DisplayName("CANCEL 대사 중 PG가 아직 승인 유지이면 취소 재시도(naverPayGateway.cancel)를 호출한다")
	@Test
	void reconcileCancel_pgApproved_callsCancelRetry() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment = unknownCancelPayment("PAY-C-3", "pg-c-3", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of());
		given(paymentRepository.findStaleCancelPaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(cancelPayment));
		given(naverPayGateway.getApprovalHistory("pg-c-3"))
			.willReturn(NaverPayHistoryResult.approved("PAY-C-3", 1000));
		given(naverPayGateway.cancel(eq("pg-c-3"), eq(1000), anyString()))
			.willReturn(NaverPayCancelResult.success());

		reconcilePaymentUseCase.reconcile();

		then(naverPayGateway).should().cancel(eq("pg-c-3"), eq(1000), anyString());
		then(succeedCancelPaymentService).should().succeed(
			eq("PAY-C-3"), eq(PaymentProvider.NAVERPAY), eq("pg-c-3"), any(LocalDateTime.class));
	}

	@DisplayName("CANCEL 재시도 결과가 SUCCESS이면 succeedCancelPaymentService를 호출한다")
	@Test
	void reconcileCancel_retrySuccess_callsSucceed() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment = unknownCancelPayment("PAY-C-4", "pg-c-4", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of());
		given(paymentRepository.findStaleCancelPaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(cancelPayment));
		given(naverPayGateway.getApprovalHistory("pg-c-4"))
			.willReturn(NaverPayHistoryResult.approved("PAY-C-4", 1000));
		given(naverPayGateway.cancel(eq("pg-c-4"), anyInt(), anyString()))
			.willReturn(NaverPayCancelResult.success());

		reconcilePaymentUseCase.reconcile();

		then(succeedCancelPaymentService).should().succeed(
			eq("PAY-C-4"), eq(PaymentProvider.NAVERPAY), eq("pg-c-4"), any(LocalDateTime.class));
		then(failCancelPaymentService).should(never()).fail(any(), any(), any(), any(), any(), any());
	}

	@DisplayName("CANCEL 재시도 결과가 ALREADY_CANCELED이면 succeedCancelPaymentService를 호출한다")
	@Test
	void reconcileCancel_retryAlreadyCanceled_callsSucceed() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment = unknownCancelPayment("PAY-C-AC", "pg-c-ac", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of());
		given(paymentRepository.findStaleCancelPaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(cancelPayment));
		given(naverPayGateway.getApprovalHistory("pg-c-ac"))
			.willReturn(NaverPayHistoryResult.approved("PAY-C-AC", 1000));
		given(naverPayGateway.cancel(eq("pg-c-ac"), anyInt(), anyString()))
			.willReturn(NaverPayCancelResult.alreadyCanceled());

		reconcilePaymentUseCase.reconcile();

		then(succeedCancelPaymentService).should().succeed(
			eq("PAY-C-AC"), eq(PaymentProvider.NAVERPAY), eq("pg-c-ac"), any(LocalDateTime.class));
	}

	@DisplayName("CANCEL 재시도 결과가 FAILED이면 failCancelPaymentService를 호출한다")
	@Test
	void reconcileCancel_retryFailed_callsFail() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment = unknownCancelPayment("PAY-C-5", "pg-c-5", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of());
		given(paymentRepository.findStaleCancelPaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(cancelPayment));
		given(naverPayGateway.getApprovalHistory("pg-c-5"))
			.willReturn(NaverPayHistoryResult.approved("PAY-C-5", 1000));
		given(naverPayGateway.cancel(eq("pg-c-5"), anyInt(), anyString()))
			.willReturn(NaverPayCancelResult.failed(PaymentFailCode.PG_REQUEST_REJECTED, "rejected"));

		reconcilePaymentUseCase.reconcile();

		then(failCancelPaymentService).should().fail(
			eq("PAY-C-5"), eq(PaymentProvider.NAVERPAY), eq("pg-c-5"),
			eq(PaymentFailCode.PG_REQUEST_REJECTED), anyString(), any(LocalDateTime.class));
		then(succeedCancelPaymentService).should(never()).succeed(any(), any(), any(), any());
	}

	@DisplayName("CANCEL 재시도 결과가 UNKNOWN이면 markUnknownCancelPaymentService를 호출한다")
	@Test
	void reconcileCancel_retryUnknown_callsMarkUnknown() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment = unknownCancelPayment("PAY-C-6", "pg-c-6", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of());
		given(paymentRepository.findStaleCancelPaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(cancelPayment));
		given(naverPayGateway.getApprovalHistory("pg-c-6"))
			.willReturn(NaverPayHistoryResult.approved("PAY-C-6", 1000));
		given(naverPayGateway.cancel(eq("pg-c-6"), anyInt(), anyString()))
			.willReturn(NaverPayCancelResult.unknown("timeout"));

		reconcilePaymentUseCase.reconcile();

		then(markUnknownCancelPaymentService).should().markUnknown(
			eq("PAY-C-6"), eq(PaymentProvider.NAVERPAY), eq("pg-c-6"), anyString(), any(LocalDateTime.class));
		then(succeedCancelPaymentService).should(never()).succeed(any(), any(), any(), any());
	}

	// --- CANCEL_RECONCILE: PENDING/HISTORY_NOT_FOUND → KEEP_WAITING ---

	@DisplayName("CANCEL 대사 중 PG 이력조회 결과가 UNKNOWN(결과 불명)이면 상태 변경하지 않는다")
	@Test
	void reconcileCancel_pgHistoryUnknown_doesNotChangeState() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment = unknownCancelPayment("PAY-C-7", "pg-c-7", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of());
		given(paymentRepository.findStaleCancelPaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(cancelPayment));
		given(naverPayGateway.getApprovalHistory("pg-c-7"))
			.willReturn(NaverPayHistoryResult.unknown("timeout"));

		reconcilePaymentUseCase.reconcile();

		then(succeedCancelPaymentService).should(never()).succeed(any(), any(), any(), any());
		then(failCancelPaymentService).should(never()).fail(any(), any(), any(), any(), any(), any());
		then(markUnknownCancelPaymentService).should(never()).markUnknown(any(), any(), any(), any(), any());
	}

	@DisplayName("CANCEL KEEP_WAITING(PG 결과 불명)이면 delayPaymentReconcileService를 호출해 backoff를 기록한다")
	@Test
	void reconcileCancel_pgHistoryUnknown_callsDelayService() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment = unknownCancelPayment("PAY-C-D1", "pg-c-d1", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of());
		given(paymentRepository.findStaleCancelPaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(cancelPayment));
		given(naverPayGateway.getApprovalHistory("pg-c-d1"))
			.willReturn(NaverPayHistoryResult.unknown("timeout"));

		reconcilePaymentUseCase.reconcile();

		then(delayPaymentReconcileService).should().delay(
			eq("PAY-C-D1"), eq(PaymentProvider.NAVERPAY), eq("pg-c-d1"), eq(PaymentType.CANCEL), any(LocalDateTime.class));
	}

	@DisplayName("CANCEL 재시도 결과가 PROCESSING이면 delayPaymentReconcileService를 호출해 backoff를 기록한다")
	@Test
	void reconcileCancel_retryProcessing_callsDelayService() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment = unknownCancelPayment("PAY-C-D2", "pg-c-d2", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of());
		given(paymentRepository.findStaleCancelPaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(cancelPayment));
		given(naverPayGateway.getApprovalHistory("pg-c-d2"))
			.willReturn(NaverPayHistoryResult.approved("PAY-C-D2", 1000));
		given(naverPayGateway.cancel(eq("pg-c-d2"), anyInt(), anyString()))
			.willReturn(NaverPayCancelResult.processing());

		reconcilePaymentUseCase.reconcile();

		then(delayPaymentReconcileService).should().delay(
			eq("PAY-C-D2"), eq(PaymentProvider.NAVERPAY), eq("pg-c-d2"), eq(PaymentType.CANCEL), any(LocalDateTime.class));
	}

	@DisplayName("CANCEL 대사 확정 분기(취소 확정)에서는 delayPaymentReconcileService를 호출하지 않는다")
	@Test
	void reconcileCancel_confirmed_doesNotCallDelayService() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment = unknownCancelPayment("PAY-C-D3", "pg-c-d3", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of());
		given(paymentRepository.findStaleCancelPaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(cancelPayment));
		given(naverPayGateway.getApprovalHistory("pg-c-d3"))
			.willReturn(NaverPayHistoryResult.canceled());

		reconcilePaymentUseCase.reconcile();

		then(delayPaymentReconcileService).should(never()).delay(any(), any(), any(), any(), any());
	}

	// --- 건별 예외 격리 ---

	@DisplayName("CANCEL 대사 중 특정 건 처리 예외가 발생해도 나머지 건을 계속 처리한다")
	@Test
	void reconcileCancel_exceptionInOne_continuesProcessingOthers() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment cancelPayment1 = unknownCancelPayment("PAY-C-8", "pg-c-8", now.minusMinutes(2));
		Payment cancelPayment2 = unknownCancelPayment("PAY-C-9", "pg-c-9", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of());
		given(paymentRepository.findStaleCancelPaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(cancelPayment1, cancelPayment2));
		given(naverPayGateway.getApprovalHistory("pg-c-8"))
			.willThrow(new RuntimeException("PG 장애"));
		given(naverPayGateway.getApprovalHistory("pg-c-9"))
			.willReturn(NaverPayHistoryResult.canceled());

		reconcilePaymentUseCase.reconcile();

		// cancelPayment1 실패해도 cancelPayment2는 처리됨
		then(succeedCancelPaymentService).should().succeed(
			eq("PAY-C-9"), eq(PaymentProvider.NAVERPAY), eq("pg-c-9"), any(LocalDateTime.class));
	}

	// --- CANCEL escalation: 6시간 초과 → escalation 통지 ---

	@DisplayName("CANCEL이 6시간 초과로 종착 못 하면 CANCEL escalation 스캔에서 escalateCancelPaymentService를 호출한다")
	@Test
	void reconcileCancel_escalation_callsEscalateCancelService() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();

		Payment cancelPayment = requestedCancelPayment("PAY-C-ESC", "pg-c-esc", now.minusHours(7));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of());
		given(paymentRepository.findStaleCancelPaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of());
		given(paymentRepository.findEscalationCandidates(any(), any(Pageable.class)))
			.willReturn(List.of());
		given(paymentRepository.findCancelEscalationCandidates(any(), any(Pageable.class)))
			.willReturn(List.of(cancelPayment));
		given(escalateCancelPaymentService.escalate(
			eq("PAY-C-ESC"), eq(PaymentProvider.NAVERPAY), eq("pg-c-esc"), any(LocalDateTime.class)))
			.willReturn(true);

		reconcilePaymentUseCase.reconcile();

		then(escalateCancelPaymentService).should().escalate(
			eq("PAY-C-ESC"), eq(PaymentProvider.NAVERPAY), eq("pg-c-esc"), any(LocalDateTime.class));
		then(notificationPort).should().notifyManualReviewRequired(
			any(), eq("PAY-C-ESC"), anyString());
	}

	@DisplayName("CANCEL FAILED는 escalation 후보에서 통지를 발화한다")
	@Test
	void reconcileCancel_failedCancelEscalation_notifiesManualReview() {
		injectPolicies();
		Payment failedCancelPayment = failedCancelPayment("PAY-C-FAIL", "pg-c-fail");

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of());
		given(paymentRepository.findStaleCancelPaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of());
		given(paymentRepository.findEscalationCandidates(any(), any(Pageable.class)))
			.willReturn(List.of());
		given(paymentRepository.findCancelEscalationCandidates(any(), any(Pageable.class)))
			.willReturn(List.of(failedCancelPayment));
		given(escalateCancelPaymentService.escalate(
			eq("PAY-C-FAIL"), eq(PaymentProvider.NAVERPAY), eq("pg-c-fail"), any(LocalDateTime.class)))
			.willReturn(true);

		reconcilePaymentUseCase.reconcile();

		then(notificationPort).should().notifyManualReviewRequired(
			any(), eq("PAY-C-FAIL"), anyString());
	}

	// --- APPROVE 대사 동작 불변 검증 ---

	@DisplayName("APPROVE 대사는 CANCEL 분기 추가 후에도 facade를 통해 확정을 시도한다")
	@Test
	void reconcileApprove_unaffectedByCancelChanges_callsConfirmFacade() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment approvePayment = unknownApprovePayment("PAY-A-1", "pg-a-1", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(approvePayment));
		given(paymentRepository.findStaleCancelPaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of());
		given(naverPayGateway.getApprovalHistory("pg-a-1"))
			.willReturn(NaverPayHistoryResult.approved("PAY-A-1", 1000));
		given(confirmApprovalUseCase.confirm(eq(approvePayment), any(LocalDateTime.class), any(), eq("PAY-A-1"), eq(1000)))
			.willReturn(ConfirmApprovalUseCase.Outcome.succeeded());

		reconcilePaymentUseCase.reconcile();

		then(confirmApprovalUseCase).should().confirm(eq(approvePayment), any(LocalDateTime.class), any(), eq("PAY-A-1"), eq(1000));
		then(succeedCancelPaymentService).should(never()).succeed(any(), any(), any(), any());
	}

	// --- helpers ---

	private Payment unknownCancelPayment(String merchantPayKey, String pgPaymentId, LocalDateTime respondedAt) {
		Payment payment = Payment.createCancelRequested(
			1L, merchantPayKey, pgPaymentId, 1000, PaymentProvider.NAVERPAY);
		payment.markUnknown("timeout", respondedAt);
		return payment;
	}

	private Payment requestedCancelPayment(String merchantPayKey, String pgPaymentId, LocalDateTime createdAt) {
		Payment payment = Payment.createCancelRequested(
			1L, merchantPayKey, pgPaymentId, 1000, PaymentProvider.NAVERPAY);
		ReflectionTestUtils.setField(payment, "createdAt", createdAt);
		return payment;
	}

	private Payment failedCancelPayment(String merchantPayKey, String pgPaymentId) {
		Payment payment = Payment.createCancelRequested(
			1L, merchantPayKey, pgPaymentId, 1000, PaymentProvider.NAVERPAY);
		payment.fail(PaymentFailCode.PG_REQUEST_REJECTED, "rejected", LocalDateTime.now());
		return payment;
	}

	private Payment unknownApprovePayment(String merchantPayKey, String pgPaymentId, LocalDateTime respondedAt) {
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, merchantPayKey, LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, pgPaymentId);
		payment.markUnknown("timeout", respondedAt);
		return payment;
	}
}
