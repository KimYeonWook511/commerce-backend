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
}
