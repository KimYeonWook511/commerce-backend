package com.commerce.payment.infrastructure;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentAttemptType;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.repository.PaymentAttemptRepository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class PaymentAttemptRepositoryAdapter implements PaymentAttemptRepository {

	private final JpaPaymentAttemptRepository jpaPaymentAttemptRepository;

	@Override
	public PaymentAttempt save(PaymentAttempt paymentAttempt) {
		return jpaPaymentAttemptRepository.saveAndFlush(paymentAttempt);
	}

	@Override
	public Optional<PaymentAttempt> findApproveAttempt(
		String merchantPayKey,
		PaymentProvider provider,
		String paymentId
	) {
		return findAttempt(
			merchantPayKey,
			provider,
			paymentId,
			PaymentAttemptType.APPROVE
		);
	}

	@Override
	public Optional<PaymentAttempt> findCancelAttempt(
		String merchantPayKey,
		PaymentProvider provider,
		String paymentId
	) {
		return findAttempt(
			merchantPayKey,
			provider,
			paymentId,
			PaymentAttemptType.CANCEL
		);
	}

	@Override
	public Optional<PaymentAttempt> findAttempt(
		String merchantPayKey,
		PaymentProvider provider,
		String paymentId,
		PaymentAttemptType type
	) {
		return jpaPaymentAttemptRepository.findByMerchantPayKeyAndProviderAndPaymentIdAndType(
			merchantPayKey,
			provider,
			paymentId,
			type
		);
	}
}
