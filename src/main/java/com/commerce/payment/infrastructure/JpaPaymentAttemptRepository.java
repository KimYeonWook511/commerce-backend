package com.commerce.payment.infrastructure;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentAttemptType;
import com.commerce.payment.domain.PaymentProvider;

public interface JpaPaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {

	Optional<PaymentAttempt> findByMerchantPayKeyAndProviderAndPaymentIdAndType(
		String merchantPayKey,
		PaymentProvider provider,
		String paymentId,
		PaymentAttemptType type
	);
}
