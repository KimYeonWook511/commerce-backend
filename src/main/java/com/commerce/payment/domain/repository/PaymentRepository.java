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

	Optional<Payment> findApprovePayment(String merchantPayKey, PaymentProvider provider, String pgPaymentId);

	Optional<Payment> findCancelPayment(String merchantPayKey, PaymentProvider provider, String pgPaymentId);

	Optional<Payment> findApproveSucceeded(String merchantPayKey);

	// APPROVE 타입 UNKNOWN 결제가 있는 주문은 reserve/approve 차단 (ADR-6)
	boolean existsUnknownByOrderId(Long orderId);

	boolean existsApprovedByOrderId(Long orderId);

	// APPROVE 대사 후보: UNKNOWN은 staleCutoff(1분), REQUESTED는 requestedStaleCutoff(15분)보다 오래됐고 escalationCutoff(6시간)보다 최근인 건.
	List<Payment> findStaleApprovePaymentsForReconciliation(LocalDateTime staleCutoff, LocalDateTime requestedStaleCutoff, LocalDateTime escalationCutoff, Pageable pageable);

	// escalation 후보: escalatedAt IS NULL이고 6시간 초과 UNKNOWN/REQUESTED APPROVE 건 (대사 스캔 윈도우 밖).
	List<Payment> findEscalationCandidates(LocalDateTime escalationCutoff, Pageable pageable);

	// 조건부 UPDATE: escalatedAt IS NULL AND status IN (UNKNOWN,REQUESTED)일 때만 escalatedAt을 기록. 영향 행 수를 반환한다.
	// 영향 행 수 1 = 이 호출이 escalation 주체. 0 = 이미 다른 주체가 처리(중복 통지 차단).
	int escalateIfPending(Long id, LocalDateTime now);
}
