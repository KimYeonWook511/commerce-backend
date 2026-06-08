package com.commerce.payment.infrastructure;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.PaymentType;

public interface JpaPaymentRepository extends JpaRepository<Payment, Long> {

	Optional<Payment> findByMerchantPayKeyAndProviderAndPgPaymentIdAndType(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		PaymentType type
	);

	Optional<Payment> findByMerchantPayKeyAndTypeAndStatus(
		String merchantPayKey,
		PaymentType type,
		PaymentStatus status
	);

	boolean existsByOrderIdAndTypeAndStatus(Long orderId, PaymentType type, PaymentStatus status);
}
