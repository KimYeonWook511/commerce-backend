package com.commerce.payment.infrastructure;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentStatus;

public interface JpaPaymentRepository extends JpaRepository<Payment, Long> {

	Optional<Payment> findByMerchantPayKey(String merchantPayKey);

	boolean existsByMerchantPayKeyAndStatus(String merchantPayKey, PaymentStatus status);
}
