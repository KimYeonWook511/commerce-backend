package com.commerce.payment.infrastructure.persistence.support;

import java.util.Optional;

import org.springframework.boot.test.context.TestComponent;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.infrastructure.persistence.JpaPaymentRepository;
import com.commerce.support.CleanupOrder;
import com.commerce.support.PersistenceTestSupport;

import lombok.RequiredArgsConstructor;

@TestComponent
@RequiredArgsConstructor
public class PaymentPersistenceTestSupport implements PersistenceTestSupport {

	private final JpaPaymentRepository jpaPaymentRepository;

	@Override
	public CleanupOrder cleanupOrder() {
		return CleanupOrder.PAYMENT;
	}

	@Override
	public void deleteAllInBatch() {
		jpaPaymentRepository.deleteAllInBatch();
	}

	public Payment save(Payment payment) {
		return jpaPaymentRepository.saveAndFlush(payment);
	}

	public Optional<Payment> findByPaymentKey(String paymentKey) {
		return jpaPaymentRepository.findByPaymentKey(paymentKey);
	}
}
