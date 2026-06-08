package com.commerce.payment.domain.repository;

import java.util.Optional;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;

public interface PaymentRepository {

	Payment save(Payment payment);

	// APPROVE 승인 완료 전용 저장 경로. uk_payment_approved_order_key 위반 시 PaymentException(PAYMENT_DUPLICATE)로 매핑.
	Payment saveApproved(Payment payment);

	Optional<Payment> findApprovePayment(String merchantPayKey, PaymentProvider provider, String pgPaymentId);

	Optional<Payment> findCancelPayment(String merchantPayKey, PaymentProvider provider, String pgPaymentId);

	Optional<Payment> findApproveSucceeded(String merchantPayKey);

	boolean existsUnknownByOrderId(Long orderId);
}
