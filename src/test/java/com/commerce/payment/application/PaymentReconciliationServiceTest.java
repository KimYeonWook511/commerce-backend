package com.commerce.payment.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.payment.application.port.PgCanceller;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.naverpay.application.port.NaverPayGateway;
import com.commerce.payment.naverpay.application.port.result.NaverPayHistoryResult;
import com.commerce.payment.postprocess.flow.PaymentPostProcessFlowPolicy;
import com.commerce.payment.postprocess.target.PaymentPostProcessTargetPolicy;

@ExtendWith(MockitoExtension.class)
class PaymentReconciliationServiceTest {

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private PaymentApprovalService paymentApprovalService;

	@Mock
	private PaymentApprovalRecordService paymentApprovalRecordService;

	@Mock
	private PaymentApprovalCompensationService paymentApprovalCompensationService;

	@Mock
	private NaverPayGateway naverPayGateway;

	@InjectMocks
	private PaymentReconciliationService reconciliationService;

	// PaymentPostProcessTargetPolicy, PaymentPostProcessFlowPolicy는 @InjectMocks가 주입하지 못하므로 실 인스턴스 직접 주입
	private final PaymentPostProcessTargetPolicy realTargetPolicy = new PaymentPostProcessTargetPolicy();
	private final PaymentPostProcessFlowPolicy realFlowPolicy = new PaymentPostProcessFlowPolicy();

	private void injectPolicies() {
		ReflectionTestUtils.setField(reconciliationService, "targetPolicy", realTargetPolicy);
		ReflectionTestUtils.setField(reconciliationService, "flowPolicy", realFlowPolicy);
	}

	// --- APPROVE_RECONCILE: PG_APPROVED → SUCCEEDED ---

	@DisplayName("UNKNOWN 결제를 대사할 때 PG가 승인을 확인하면 succeedApproval을 호출한다")
	@Test
	void reconcile_unknownPgApproved_callsSucceedApproval() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = unknownApprovePayment("PAY-1", "pg-1", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));
		given(naverPayGateway.getApprovalHistory("pg-1"))
			.willReturn(NaverPayHistoryResult.approved("PAY-1", 1000));

		reconciliationService.reconcile();

		then(paymentApprovalService).should().succeedApproval(eq(payment), any(LocalDateTime.class));
	}

	// --- APPROVE_RECONCILE: PG_CANCELED → FAILED(ALREADY_CANCELED) ---

	@DisplayName("UNKNOWN 결제를 대사할 때 PG가 취소를 확인하면 ALREADY_CANCELED로 fail을 호출한다")
	@Test
	void reconcile_unknownPgCanceled_callsFailWithAlreadyCanceled() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = unknownApprovePayment("PAY-1", "pg-1", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));
		given(naverPayGateway.getApprovalHistory("pg-1"))
			.willReturn(NaverPayHistoryResult.canceled());

		reconciliationService.reconcile();

		then(paymentApprovalRecordService).should().fail(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-1"),
			eq(PaymentFailCode.ALREADY_CANCELED), any(String.class), any(LocalDateTime.class)
		);
		then(paymentApprovalService).should(never()).succeedApproval(any(), any());
	}

	// --- APPROVE_RECONCILE: PENDING/HISTORY_NOT_FOUND → KEEP_WAITING ---

	@DisplayName("PG 이력조회 결과가 UNKNOWN(결과 불명)이면 아무 상태 전이도 하지 않는다")
	@Test
	void reconcile_pgHistoryUnknown_doesNotChangeState() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = unknownApprovePayment("PAY-1", "pg-1", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));
		given(naverPayGateway.getApprovalHistory("pg-1"))
			.willReturn(NaverPayHistoryResult.unknown("timeout"));

		reconciliationService.reconcile();

		then(paymentApprovalService).should(never()).succeedApproval(any(), any());
		then(paymentApprovalRecordService).should(never()).fail(any(), any(), any(), any(), any(), any());
	}

	@DisplayName("PG 이력조회 결과가 FAILED(이력 없음)이면 아무 상태 전이도 하지 않는다")
	@Test
	void reconcile_pgHistoryFailed_doesNotChangeState() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = unknownApprovePayment("PAY-1", "pg-1", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));
		given(naverPayGateway.getApprovalHistory("pg-1"))
			.willReturn(NaverPayHistoryResult.failed(com.commerce.payment.exception.PaymentErrorCode.PAYMENT_NOT_FOUND));

		reconciliationService.reconcile();

		then(paymentApprovalService).should(never()).succeedApproval(any(), any());
		then(paymentApprovalRecordService).should(never()).fail(any(), any(), any(), any(), any(), any());
	}

	// --- escalation: 6시간 초과는 로그만 남기고 상태 변경 없음 (ADR-L5, 후속 #238) ---

	@DisplayName("UNKNOWN 결제가 6시간 escalation 임계를 넘으면 상태 변경 없이 PG 조회도 하지 않는다")
	@Test
	void reconcile_unknownEscalated_doesNotChangeState() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = unknownApprovePayment("PAY-1", "pg-1", now.minusHours(7));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));

		reconciliationService.reconcile();

		then(naverPayGateway).should(never()).getApprovalHistory(any());
		then(paymentApprovalService).should(never()).succeedApproval(any(), any());
	}

	@DisplayName("stale REQUESTED 결제가 6시간 escalation 임계를 넘으면 상태 변경 없이 PG 조회도 하지 않는다")
	@Test
	void reconcile_requestedEscalated_doesNotChangeState() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = requestedApprovePayment("PAY-1", "pg-1", now.minusHours(7));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));

		reconciliationService.reconcile();

		then(naverPayGateway).should(never()).getApprovalHistory(any());
	}

	// --- 멱등: 이미 확정된 건은 후보에서 제외되어야 하므로 쿼리 레벨 보장. 정책 NONE 케이스 ---

	@DisplayName("UNKNOWN이지만 1분 임계 미달이면 아무 처리도 하지 않는다 (NONE 반환)")
	@Test
	void reconcile_unknownBelowThreshold_doesNothing() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = unknownApprovePayment("PAY-1", "pg-1", now.minusSeconds(30));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));

		reconciliationService.reconcile();

		then(naverPayGateway).should(never()).getApprovalHistory(any());
		then(paymentApprovalService).should(never()).succeedApproval(any(), any());
	}

	// --- 주문 CANCELED → 보상 취소 (C) ---

	@DisplayName("succeedApproval이 ORDER_PAID_NOT_ALLOWED이고 주문이 CANCELED이면 compensateCanceledOrderApproval을 호출한다")
	@Test
	void reconcile_orderCanceled_callsCompensation() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = unknownApprovePayment("PAY-1", "pg-1", now.minusMinutes(2));
		Order canceledOrder = canceledOrder();

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));
		given(naverPayGateway.getApprovalHistory("pg-1"))
			.willReturn(NaverPayHistoryResult.approved("PAY-1", 1000));
		willThrow(new OrderException(OrderErrorCode.ORDER_PAID_NOT_ALLOWED))
			.given(paymentApprovalService).succeedApproval(any(), any());
		given(orderRepository.findById(payment.getOrderId())).willReturn(Optional.of(canceledOrder));

		reconciliationService.reconcile();

		then(paymentApprovalCompensationService).should()
			.compensateCanceledOrderApproval(eq(payment), any(PgCanceller.class));
	}

	@DisplayName("succeedApproval이 ORDER_PAID_NOT_ALLOWED이고 주문이 PAID이며 중복 결제가 없으면 succeedApprovalRecordOnly를 호출한다")
	@Test
	void reconcile_orderPaidNoDuplicate_succeedsPaymentRecord() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = unknownApprovePayment("PAY-1", "pg-1", now.minusMinutes(2));
		Order paidOrder = paidOrder();

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));
		given(naverPayGateway.getApprovalHistory("pg-1"))
			.willReturn(NaverPayHistoryResult.approved("PAY-1", 1000));
		willThrow(new OrderException(OrderErrorCode.ORDER_PAID_NOT_ALLOWED))
			.given(paymentApprovalService).succeedApproval(any(), any());
		given(orderRepository.findById(payment.getOrderId())).willReturn(Optional.of(paidOrder));
		given(paymentRepository.existsApprovedByOrderId(payment.getOrderId())).willReturn(false);

		reconciliationService.reconcile();

		then(paymentApprovalService).should().succeedApprovalRecordOnly(eq(payment), any(LocalDateTime.class));
		then(paymentApprovalCompensationService).should(never())
			.compensateCanceledOrderApproval(any(), any());
		then(paymentApprovalCompensationService).should(never())
			.compensateDuplicatePayment(any(), any(), any());
	}

	@DisplayName("succeedApproval이 ORDER_PAID_NOT_ALLOWED이고 주문이 PAID이며 중복 결제가 있으면 compensateDuplicatePayment를 호출한다")
	@Test
	void reconcile_orderPaidWithDuplicate_compensatesDuplicate() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = unknownApprovePayment("PAY-1", "pg-1", now.minusMinutes(2));
		Order paidOrder = paidOrder();

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));
		given(naverPayGateway.getApprovalHistory("pg-1"))
			.willReturn(NaverPayHistoryResult.approved("PAY-1", 1000));
		willThrow(new OrderException(OrderErrorCode.ORDER_PAID_NOT_ALLOWED))
			.given(paymentApprovalService).succeedApproval(any(), any());
		given(orderRepository.findById(payment.getOrderId())).willReturn(Optional.of(paidOrder));
		given(paymentRepository.existsApprovedByOrderId(payment.getOrderId())).willReturn(true);

		reconciliationService.reconcile();

		then(paymentApprovalCompensationService).should()
			.compensateDuplicatePayment(eq(payment), any(Exception.class), any(PgCanceller.class));
		then(paymentApprovalService).should(never()).succeedApprovalRecordOnly(any(), any());
	}

	@DisplayName("succeedApproval이 ORDER_PAID_NOT_ALLOWED이고 주문이 null이면 FAILED로 종착하고 재시도하지 않는다")
	@Test
	void reconcile_orderNull_terminatesAsFailed() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = unknownApprovePayment("PAY-1", "pg-1", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));
		given(naverPayGateway.getApprovalHistory("pg-1"))
			.willReturn(NaverPayHistoryResult.approved("PAY-1", 1000));
		willThrow(new OrderException(OrderErrorCode.ORDER_PAID_NOT_ALLOWED))
			.given(paymentApprovalService).succeedApproval(any(), any());
		given(orderRepository.findById(payment.getOrderId())).willReturn(Optional.empty());

		reconciliationService.reconcile();

		then(paymentApprovalRecordService).should().fail(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-1"),
			eq(PaymentFailCode.APPROVE_PROCESS_FAILED), any(String.class), any(LocalDateTime.class)
		);
		then(paymentApprovalCompensationService).should(never()).compensateCanceledOrderApproval(any(), any());
	}

	@DisplayName("succeedApproval이 ORDER_PAID_NOT_ALLOWED이고 주문이 비-INIT(RECEIVED 등)이면 FAILED로 종착한다")
	@Test
	void reconcile_orderOtherNonInit_terminatesAsFailed() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment = unknownApprovePayment("PAY-1", "pg-1", now.minusMinutes(2));
		Order receivedOrder = receivedOrder();

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment));
		given(naverPayGateway.getApprovalHistory("pg-1"))
			.willReturn(NaverPayHistoryResult.approved("PAY-1", 1000));
		willThrow(new OrderException(OrderErrorCode.ORDER_PAID_NOT_ALLOWED))
			.given(paymentApprovalService).succeedApproval(any(), any());
		given(orderRepository.findById(payment.getOrderId())).willReturn(Optional.of(receivedOrder));

		reconciliationService.reconcile();

		then(paymentApprovalRecordService).should().fail(
			eq("PAY-1"), eq(PaymentProvider.NAVERPAY), eq("pg-1"),
			eq(PaymentFailCode.APPROVE_PROCESS_FAILED), any(String.class), any(LocalDateTime.class)
		);
		then(paymentApprovalCompensationService).should(never()).compensateCanceledOrderApproval(any(), any());
	}

	// --- 건별 예외 격리 ---

	@DisplayName("특정 건 처리 중 예외가 발생해도 나머지 건을 계속 처리한다")
	@Test
	void reconcile_exceptionInOnePayment_continuesProcessingOthers() {
		injectPolicies();
		LocalDateTime now = LocalDateTime.now();
		Payment payment1 = unknownApprovePayment("PAY-1", "pg-1", now.minusMinutes(2));
		Payment payment2 = unknownApprovePayment("PAY-2", "pg-2", now.minusMinutes(2));

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(Pageable.class)))
			.willReturn(List.of(payment1, payment2));
		given(naverPayGateway.getApprovalHistory("pg-1"))
			.willThrow(new RuntimeException("PG 장애"));
		given(naverPayGateway.getApprovalHistory("pg-2"))
			.willReturn(NaverPayHistoryResult.approved("PAY-2", 1000));

		reconciliationService.reconcile();

		// payment1 실패해도 payment2는 처리
		then(paymentApprovalService).should().succeedApproval(eq(payment2), any(LocalDateTime.class));
	}

	// --- 후보 없음 ---

	@DisplayName("대사 후보가 없으면 PG 조회나 상태 전이를 하지 않는다")
	@Test
	void reconcile_noCandidates_doesNothing() {
		injectPolicies();

		given(paymentRepository.findStaleApprovePaymentsForReconciliation(any(), any(), any(Pageable.class)))
			.willReturn(List.of());

		reconciliationService.reconcile();

		then(naverPayGateway).should(never()).getApprovalHistory(any());
		then(paymentApprovalService).should(never()).succeedApproval(any(), any());
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

	private Order canceledOrder() {
		Order order = Order.create(1L);
		order.cancel();
		return order;
	}

	private Order paidOrder() {
		Order order = Order.create(1L);
		order.completePayment();
		return order;
	}

	private Order receivedOrder() {
		Order order = Order.create(1L);
		ReflectionTestUtils.setField(order, "status", OrderStatus.RECEIVED);
		return order;
	}
}
