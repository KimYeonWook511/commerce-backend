package com.commerce.payment.application.usecase;

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

import com.commerce.payment.application.port.NotificationPort;
import com.commerce.payment.application.service.DelayPaymentReconcileService;
import com.commerce.payment.application.service.EscalateApprovePaymentService;
import com.commerce.payment.application.service.EscalateCancelPaymentService;
import com.commerce.payment.application.service.FailApprovePaymentService;
import com.commerce.payment.application.service.FailCancelPaymentService;
import com.commerce.payment.application.service.MarkUnknownCancelPaymentService;
import com.commerce.payment.application.service.SucceedCancelPaymentService;
import com.commerce.payment.application.service.SucceedPaymentApprovalService;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.naverpay.application.port.NaverPayGateway;
import com.commerce.payment.naverpay.application.port.result.NaverPayHistoryResult;
import com.commerce.payment.postprocess.flow.PaymentPostProcessFlowPolicy;
import com.commerce.payment.postprocess.target.PaymentPostProcessTargetPolicy;

@ExtendWith(MockitoExtension.class)
class ReconcilePaymentUseCaseTest {

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

	// PaymentPostProcessTargetPolicy, PaymentPostProcessFlowPolicy는 @InjectMocks가 주입하지 못하므로 실 인스턴스 직접 주입
	private final PaymentPostProcessTargetPolicy realTargetPolicy = new PaymentPostProcessTargetPolicy();
	private final PaymentPostProcessFlowPolicy realFlowPolicy = new PaymentPostProcessFlowPolicy();

	private void injectPolicies() {
		ReflectionTestUtils.setField(reconcilePaymentUseCase, "targetPolicy", realTargetPolicy);
		ReflectionTestUtils.setField(reconcilePaymentUseCase, "flowPolicy", realFlowPolicy);
	}

	// --- APPROVE_RECONCILE: PG_APPROVED → facade 위임 → SUCCEEDED ---

	@DisplayName("UNKNOWN 결제를 대사할 때 PG가 승인을 확인하면 confirmApprovalUseCase.confirm을 호출한다")
	@Test
	void reconcile_unknownPgApproved_callsConfirmFacade() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = unknownApprovePayment("PAY-1", "pg-1", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));
		given(naverPayGateway.getApprovalHistory("pg-1"))
			.willReturn(NaverPayHistoryResult.approved("PAY-1", 1000));
		given(confirmApprovalUseCase.confirm(eq(payment), any(LocalDateTime.class), any(), eq("PAY-1"), eq(1000)))
			.willReturn(ConfirmApprovalUseCase.Outcome.succeeded());

		reconcilePaymentUseCase.reconcile();

		then(confirmApprovalUseCase).should().confirm(eq(payment), any(LocalDateTime.class), any(), eq("PAY-1"), eq(1000));
	}

	// --- APPROVE_RECONCILE: PG_CANCELED → FAILED(ALREADY_CANCELED) ---

	@DisplayName("UNKNOWN 결제를 대사할 때 PG가 취소를 확인하면 ALREADY_CANCELED로 fail을 호출한다")
	@Test
	void reconcile_unknownPgCanceled_callsFailWithAlreadyCanceled() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = unknownApprovePayment("PAY-1", "pg-1", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));
		given(naverPayGateway.getApprovalHistory("pg-1"))
			.willReturn(NaverPayHistoryResult.canceled());

		reconcilePaymentUseCase.reconcile();

		then(failApprovePaymentService).should().fail(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-1"),
			eq(PaymentFailCode.ALREADY_CANCELED), any(String.class), any(LocalDateTime.class)
		);
		then(confirmApprovalUseCase).should(never()).confirm(any(), any(), any(), anyString(), anyInt());
	}

	// --- APPROVE_RECONCILE: PENDING/HISTORY_NOT_FOUND → KEEP_WAITING ---

	@DisplayName("PG 이력조회 결과가 UNKNOWN(결과 불명)이면 아무 상태 전이도 하지 않는다")
	@Test
	void reconcile_pgHistoryUnknown_doesNotChangeState() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = unknownApprovePayment("PAY-1", "pg-1", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));
		given(naverPayGateway.getApprovalHistory("pg-1"))
			.willReturn(NaverPayHistoryResult.unknown("timeout"));

		reconcilePaymentUseCase.reconcile();

		then(confirmApprovalUseCase).should(never()).confirm(any(), any(), any(), anyString(), anyInt());
		then(failApprovePaymentService).should(never()).fail(any(), any(), any(), any(), any(), any());
	}

	@DisplayName("PG 이력조회 결과가 FAILED(이력 없음)이면 아무 상태 전이도 하지 않는다")
	@Test
	void reconcile_pgHistoryFailed_doesNotChangeState() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = unknownApprovePayment("PAY-1", "pg-1", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));
		given(naverPayGateway.getApprovalHistory("pg-1"))
			.willReturn(NaverPayHistoryResult.failed(com.commerce.payment.domain.exception.PaymentErrorCode.PAYMENT_NOT_FOUND));

		reconcilePaymentUseCase.reconcile();

		then(confirmApprovalUseCase).should(never()).confirm(any(), any(), any(), anyString(), anyInt());
		then(failApprovePaymentService).should(never()).fail(any(), any(), any(), any(), any(), any());
	}

	@DisplayName("APPROVE KEEP_WAITING(PG 결과 불명)이면 delayPaymentReconcileService를 호출해 backoff를 기록한다")
	@Test
	void reconcile_pgHistoryUnknown_callsDelayService() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = unknownApprovePayment("PAY-1", "pg-1", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));
		given(naverPayGateway.getApprovalHistory("pg-1"))
			.willReturn(NaverPayHistoryResult.unknown("timeout"));

		reconcilePaymentUseCase.reconcile();

		then(delayPaymentReconcileService).should().delay(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-1"), eq(PaymentType.APPROVE), any(LocalDateTime.class));
	}

	@DisplayName("APPROVE 대사 확정 분기(승인/취소 확정)에서는 delayPaymentReconcileService를 호출하지 않는다")
	@Test
	void reconcile_approveConfirmed_doesNotCallDelayService() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = unknownApprovePayment("PAY-1", "pg-1", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));
		given(naverPayGateway.getApprovalHistory("pg-1"))
			.willReturn(NaverPayHistoryResult.approved("PAY-1", 1000));
		given(confirmApprovalUseCase.confirm(eq(payment), any(LocalDateTime.class), any(), eq("PAY-1"), eq(1000)))
			.willReturn(ConfirmApprovalUseCase.Outcome.succeeded());

		reconcilePaymentUseCase.reconcile();

		then(delayPaymentReconcileService).should(never()).delay(any(), any(), any(), any(), any());
	}

	// --- escalation: 6시간 초과는 로그만 남기고 상태 변경 없음 (ADR-L5, 후속 #238) ---

	@DisplayName("UNKNOWN 결제가 6시간 escalation 임계를 넘으면 상태 변경 없이 PG 조회도 하지 않는다")
	@Test
	void reconcile_unknownEscalated_doesNotChangeState() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = unknownApprovePayment("PAY-1", "pg-1", now.minusHours(7));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));

		reconcilePaymentUseCase.reconcile();

		then(naverPayGateway).should(never()).getApprovalHistory(any());
		then(confirmApprovalUseCase).should(never()).confirm(any(), any(), any(), anyString(), anyInt());
	}

	@DisplayName("stale REQUESTED 결제가 6시간 escalation 임계를 넘으면 상태 변경 없이 PG 조회도 하지 않는다")
	@Test
	void reconcile_requestedEscalated_doesNotChangeState() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = requestedApprovePayment("PAY-1", "pg-1", now.minusHours(7));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));

		reconcilePaymentUseCase.reconcile();

		then(naverPayGateway).should(never()).getApprovalHistory(any());
	}

	// --- 멱등: 이미 확정된 건은 후보에서 제외되어야 하므로 쿼리 레벨 보장. 정책 NONE 케이스 ---

	@DisplayName("UNKNOWN이지만 1분 임계 미달이면 아무 처리도 하지 않는다 (NONE 반환)")
	@Test
	void reconcile_unknownBelowThreshold_doesNothing() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = unknownApprovePayment("PAY-1", "pg-1", now.minusSeconds(30));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));

		reconcilePaymentUseCase.reconcile();

		then(naverPayGateway).should(never()).getApprovalHistory(any());
		then(confirmApprovalUseCase).should(never()).confirm(any(), any(), any(), anyString(), anyInt());
	}

	// --- 주문 CANCELED → facade가 보상 (errorCode 기반, 주문 재조회 없음) ---

	@DisplayName("facade가 ORDER_CANCELED_FOR_PAYMENT 거부를 반환하면 FAILED로 종착한다")
	@Test
	void reconcile_facadeRejectsWithCanceledOrder_terminatesAsFailed() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = unknownApprovePayment("PAY-1", "pg-1", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));
		given(naverPayGateway.getApprovalHistory("pg-1"))
			.willReturn(NaverPayHistoryResult.approved("PAY-1", 1000));
		given(confirmApprovalUseCase.confirm(eq(payment), any(LocalDateTime.class), any(), eq("PAY-1"), eq(1000)))
			.willReturn(ConfirmApprovalUseCase.Outcome.rejected(PaymentErrorCode.PAYMENT_DUPLICATE));

		reconcilePaymentUseCase.reconcile();

		then(confirmApprovalUseCase).should().confirm(eq(payment), any(LocalDateTime.class), any(), eq("PAY-1"), eq(1000));
		// facade가 보상 처리 후 Rejected를 반환했으므로 ReconcilePaymentUseCase는 FAILED로 번역
	}

	// --- 중복 PAID → facade가 보상 ---

	@DisplayName("facade가 PAYMENT_DUPLICATE 거부를 반환하면(중복) FAILED로 종착한다")
	@Test
	void reconcile_facadeRejectsDuplicate_terminatesAsFailed() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = unknownApprovePayment("PAY-1", "pg-1", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));
		given(naverPayGateway.getApprovalHistory("pg-1"))
			.willReturn(NaverPayHistoryResult.approved("PAY-1", 1000));
		given(confirmApprovalUseCase.confirm(eq(payment), any(LocalDateTime.class), any(), eq("PAY-1"), eq(1000)))
			.willReturn(ConfirmApprovalUseCase.Outcome.rejected(PaymentErrorCode.PAYMENT_DUPLICATE));

		reconcilePaymentUseCase.reconcile();

		then(confirmApprovalUseCase).should().confirm(eq(payment), any(LocalDateTime.class), any(), eq("PAY-1"), eq(1000));
	}

	// --- 비중복 PAID → facade가 통지+fail 안전망 (ADR-L3) ---

	@DisplayName("facade가 PAYMENT_APPROVE_FAILED 거부를 반환하면(비중복 PAID 안전망) FAILED로 종착한다")
	@Test
	void reconcile_facadeRejectsNonDuplicatePaid_terminatesAsFailed() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = unknownApprovePayment("PAY-1", "pg-1", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));
		given(naverPayGateway.getApprovalHistory("pg-1"))
			.willReturn(NaverPayHistoryResult.approved("PAY-1", 1000));
		given(confirmApprovalUseCase.confirm(eq(payment), any(LocalDateTime.class), any(), eq("PAY-1"), eq(1000)))
			.willReturn(ConfirmApprovalUseCase.Outcome.rejected(PaymentErrorCode.PAYMENT_APPROVE_FAILED));

		reconcilePaymentUseCase.reconcile();

		then(confirmApprovalUseCase).should().confirm(eq(payment), any(LocalDateTime.class), any(), eq("PAY-1"), eq(1000));
	}

	// --- merchantPayKey 불일치 → facade가 보상 (ADR-L2, 금전 정합성) ---

	@DisplayName("대사 APPROVED인데 merchantPayKey가 불일치하면 facade가 보상을 처리하고 FAILED로 종착한다")
	@Test
	void reconcile_approvedKeyMismatch_facadeHandlesCompensation() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = unknownApprovePayment("PAY-1", "pg-1", now.minusMinutes(2));

		// real ConfirmApprovalUseCase로 교체해 실제 보상 흐름을 검증한다
		SucceedPaymentApprovalService succeedPaymentApprovalServiceMock = org.mockito.Mockito.mock(SucceedPaymentApprovalService.class);
		CompensateApprovalUseCase compensateApprovalUseCaseMock = org.mockito.Mockito.mock(CompensateApprovalUseCase.class);
		ConfirmApprovalUseCase realConfirmApprovalUseCase = new ConfirmApprovalUseCase(
			succeedPaymentApprovalServiceMock,
			compensateApprovalUseCaseMock,
			failApprovePaymentService,
			paymentRepository,
			notificationPort
		);
		ReflectionTestUtils.setField(reconcilePaymentUseCase, "confirmApprovalUseCase", realConfirmApprovalUseCase);

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));
		// merchantPayKey 불일치 — payment.merchantPayKey="PAY-1", historyResult.merchantPayKey="PAY-OTHER"
		given(naverPayGateway.getApprovalHistory("pg-1"))
			.willReturn(NaverPayHistoryResult.approved("PAY-OTHER", 1000));

		reconcilePaymentUseCase.reconcile();

		// verifyApprovedResponse가 PAYMENT_MERCHANT_KEY_MISMATCH를 던지고 facade가 compensateMerchantKeyMismatch를 호출한다
		then(compensateApprovalUseCaseMock).should().compensateMerchantKeyMismatch(eq(payment));
	}

	// --- 건별 예외 격리 ---

	@DisplayName("특정 건 처리 중 예외가 발생해도 나머지 건을 계속 처리한다")
	@Test
	void reconcile_exceptionInOnePayment_continuesProcessingOthers() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment1 = unknownApprovePayment("PAY-1", "pg-1", now.minusMinutes(2));
		Payment payment2 = unknownApprovePayment("PAY-2", "pg-2", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment1, payment2));
		given(naverPayGateway.getApprovalHistory("pg-1"))
			.willThrow(new RuntimeException("PG 장애"));
		given(naverPayGateway.getApprovalHistory("pg-2"))
			.willReturn(NaverPayHistoryResult.approved("PAY-2", 1000));
		given(confirmApprovalUseCase.confirm(eq(payment2), any(LocalDateTime.class), any(), eq("PAY-2"), eq(1000)))
			.willReturn(ConfirmApprovalUseCase.Outcome.succeeded());

		reconcilePaymentUseCase.reconcile();

		// payment1 실패해도 payment2는 처리
		then(confirmApprovalUseCase).should().confirm(eq(payment2), any(LocalDateTime.class), any(), eq("PAY-2"), eq(1000));
	}

	// --- 후보 없음 ---

	@DisplayName("대사 후보가 없으면 PG 조회나 상태 전이를 하지 않는다")
	@Test
	void reconcile_noCandidates_doesNothing() {
		injectPolicies();

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(), any(), any(Pageable.class)))
			.willReturn(List.of());

		reconcilePaymentUseCase.reconcile();

		then(naverPayGateway).should(never()).getApprovalHistory(any());
		then(confirmApprovalUseCase).should(never()).confirm(any(), any(), any(), anyString(), anyInt());
	}

	// --- helpers ---

	private Payment unknownApprovePayment(String merchantPayKey, String pgPaymentId, LocalDateTime respondedAt) {
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, merchantPayKey, LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, pgPaymentId);
		payment.markUnknown("timeout", respondedAt);
		return payment;
	}

	private Payment requestedApprovePayment(String merchantPayKey, String pgPaymentId, LocalDateTime createdAt) {
		PaymentReservation reservation = PaymentReservation.createReserved(
			1L, 1L, 1000, PaymentProvider.NAVERPAY, merchantPayKey, LocalDateTime.now().plusMinutes(15));
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, pgPaymentId);
		ReflectionTestUtils.setField(payment, "createdAt", createdAt);
		return payment;
	}
}
