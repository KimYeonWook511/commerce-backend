package com.commerce.payment.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;

public interface PaymentRepository {

	Payment save(Payment payment);

	// APPROVE 승인 완료 전용 저장 경로. uk_payment_approved_order_key 위반 시 PaymentException(PAYMENT_DUPLICATE)로 매핑.
	Payment saveApproved(Payment payment);

	// 낙관 락(@Version) 충돌을 PaymentException(PAYMENT_CONCURRENTLY_MODIFIED)으로 변환하는 전용 저장 경로.
	// skip/전파 분기가 필요한 전이(markUnknown/fail 등)에서 사용한다. 정책(skip/전파)은 호출하는 useCase가 결정한다.
	Payment saveChecked(Payment payment);

	Optional<Payment> findApprovePayment(String merchantPayKey, PaymentProvider provider, String pgPaymentId);

	Optional<Payment> findCancelPayment(String merchantPayKey, PaymentProvider provider, String pgPaymentId);

	Optional<Payment> findApproveSucceeded(String merchantPayKey);

	// 환불 대상 조회: orderId로 SUCCEEDED APPROVE 결제를 가져온다. 주문당 SUCCEEDED APPROVE는 유일하다.
	Optional<Payment> findApproveSucceededByOrderId(Long orderId);

	// APPROVE 타입 UNKNOWN 결제가 있는 주문은 reserve/approve 차단
	boolean existsUnknownByOrderId(Long orderId);

	// APPROVE 타입 미확정(UNKNOWN/REQUESTED) 결제가 있는 주문은 환불 불가(PG 상태 불확실)
	boolean existsUnconfirmedApproveByOrderId(Long orderId);

	boolean existsApprovedByOrderId(Long orderId);

	// APPROVE 대사 후보: UNKNOWN은 staleCutoff(1분), REQUESTED는 requestedStaleCutoff(15분)보다 오래됐고 escalationCutoff(6시간)보다 최근인 건.
	// backoff 게이트: next_reconcile_at IS NULL이거나 now 이하인 건만 포함(ADR-L1).
	List<Payment> findStaleApprovePaymentsForReconciliation(LocalDateTime staleCutoff, LocalDateTime requestedStaleCutoff, LocalDateTime escalationCutoff, LocalDateTime now, Pageable pageable);

	// escalation 후보: escalatedAt IS NULL이고 6시간 초과 UNKNOWN/REQUESTED APPROVE 건 (대사 스캔 윈도우 밖).
	List<Payment> findEscalationCandidates(LocalDateTime escalationCutoff, Pageable pageable);

	// CANCEL 대사 후보: APPROVE 스캔과 동일 cutoff·페이징 정책. UNKNOWN은 staleCutoff(1분), REQUESTED는 requestedStaleCutoff(15분) 기준 (ADR-L4).
	// backoff 게이트: next_reconcile_at IS NULL이거나 now 이하인 건만 포함(ADR-L1).
	List<Payment> findStaleCancelPaymentsForReconciliation(LocalDateTime staleCutoff, LocalDateTime requestedStaleCutoff, LocalDateTime escalationCutoff, LocalDateTime now, Pageable pageable);

	// CANCEL escalation 후보: escalatedAt IS NULL이고 6시간 초과 UNKNOWN/REQUESTED CANCEL 건 + FAILED CANCEL(확정적 환불 실패).
	List<Payment> findCancelEscalationCandidates(LocalDateTime escalationCutoff, Pageable pageable);
}
