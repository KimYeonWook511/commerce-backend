package com.commerce.payment.domain.repository;

import java.util.Optional;

import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentAttemptType;
import com.commerce.payment.domain.PaymentProvider;

public interface PaymentAttemptRepository {

	PaymentAttempt save(PaymentAttempt paymentAttempt);

	Optional<PaymentAttempt> findApproveAttempt(String merchantPayKey, PaymentProvider provider, String pgPaymentId);

	Optional<PaymentAttempt> findCancelAttempt(String merchantPayKey, PaymentProvider provider, String pgPaymentId);

	Optional<PaymentAttempt> findAttempt(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		PaymentAttemptType type
	);
}
