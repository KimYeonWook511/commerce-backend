package com.commerce.payment.legacy.infrastructure.persistence.support;

import java.util.Optional;

import org.springframework.boot.test.context.TestComponent;

import com.commerce.payment.legacy.domain.Payment;
import com.commerce.payment.legacy.domain.PaymentProvider;
import com.commerce.payment.legacy.domain.PaymentStatus;
import com.commerce.payment.legacy.domain.PaymentType;
import com.commerce.payment.legacy.infrastructure.persistence.JpaPaymentRepository;

import lombok.RequiredArgsConstructor;
import com.commerce.support.CleanupOrder;
import com.commerce.support.PersistenceTestSupport;

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
		return jpaPaymentRepository.save(payment);
	}

	public Optional<Payment> findApproveSucceeded(String merchantPayKey) {
		return jpaPaymentRepository.findByMerchantPayKeyAndTypeAndStatus(
			merchantPayKey, PaymentType.APPROVE, PaymentStatus.SUCCEEDED
		);
	}

	public long countPaymentsByMerchantPayKey(String merchantPayKey) {
		return jpaPaymentRepository.findAll().stream()
			.filter(payment -> payment.getMerchantPayKey().equals(merchantPayKey))
			.count();
	}

	public Optional<Payment> findPayment(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		PaymentType type
	) {
		return jpaPaymentRepository.findByMerchantPayKeyAndProviderAndPgPaymentIdAndType(
			merchantPayKey, provider, pgPaymentId, type
		);
	}

	public Payment getPayment(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		PaymentType type
	) {
		return findPayment(merchantPayKey, provider, pgPaymentId, type).orElseThrow();
	}

	public long countPayments(String merchantPayKey, String pgPaymentId, PaymentType type) {
		return jpaPaymentRepository.findAll().stream()
			.filter(payment -> payment.getMerchantPayKey().equals(merchantPayKey))
			.filter(payment -> payment.getPgPaymentId().equals(pgPaymentId))
			.filter(payment -> payment.getType() == type)
			.count();
	}

	public long countCancelPayments(String merchantPayKey) {
		return jpaPaymentRepository.findAll().stream()
			.filter(payment -> payment.getMerchantPayKey().equals(merchantPayKey))
			.filter(payment -> payment.getType() == PaymentType.CANCEL)
			.count();
	}
}
