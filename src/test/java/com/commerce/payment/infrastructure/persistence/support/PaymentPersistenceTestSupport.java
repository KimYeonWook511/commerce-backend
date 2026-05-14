package com.commerce.payment.infrastructure.persistence.support;

import java.util.Optional;

import org.springframework.boot.test.context.TestComponent;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentAttemptType;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.infrastructure.JpaPaymentAttemptRepository;
import com.commerce.payment.infrastructure.JpaPaymentRepository;

import lombok.RequiredArgsConstructor;
import com.commerce.support.CleanupOrder;
import com.commerce.support.PersistenceTestSupport;

@TestComponent
@RequiredArgsConstructor
public class PaymentPersistenceTestSupport implements PersistenceTestSupport {

	private final JpaPaymentRepository paymentRepository;
	private final JpaPaymentAttemptRepository paymentAttemptRepository;

	@Override
	public CleanupOrder cleanupOrder() {
		return CleanupOrder.PAYMENT;
	}

	@Override
	public void deleteAllInBatch() {
		paymentAttemptRepository.deleteAllInBatch();
		paymentRepository.deleteAllInBatch();
	}

	public PaymentAttempt save(PaymentAttempt paymentAttempt) {
		return paymentAttemptRepository.save(paymentAttempt);
	}

	public Payment save(Payment payment) {
		return paymentRepository.save(payment);
	}

	public Optional<Payment> findPaymentByMerchantPayKey(String merchantPayKey) {
		return paymentRepository.findByMerchantPayKey(merchantPayKey);
	}

	public long countPaymentsByMerchantPayKey(String merchantPayKey) {
		return paymentRepository.findAll().stream()
			.filter(payment -> payment.getMerchantPayKey().equals(merchantPayKey))
			.count();
	}

	public Optional<PaymentAttempt> findAttempt(
		String merchantPayKey,
		PaymentProvider provider,
		String paymentId,
		PaymentAttemptType type
	) {
		return paymentAttemptRepository.findByMerchantPayKeyAndProviderAndPaymentIdAndType(
			merchantPayKey,
			provider,
			paymentId,
			type
		);
	}

	public PaymentAttempt getAttempt(
		String merchantPayKey,
		PaymentProvider provider,
		String paymentId,
		PaymentAttemptType type
	) {
		return findAttempt(merchantPayKey, provider, paymentId, type).orElseThrow();
	}

	public long countAttempts(String merchantPayKey, String paymentId, PaymentAttemptType type) {
		return paymentAttemptRepository.findAll().stream()
			.filter(attempt -> attempt.getMerchantPayKey().equals(merchantPayKey))
			.filter(attempt -> attempt.getPaymentId().equals(paymentId))
			.filter(attempt -> attempt.getType() == type)
			.count();
	}

	public long countCancelAttempts(String merchantPayKey) {
		return paymentAttemptRepository.findAll().stream()
			.filter(attempt -> attempt.getMerchantPayKey().equals(merchantPayKey))
			.filter(attempt -> attempt.getType() == PaymentAttemptType.CANCEL)
			.count();
	}
}
