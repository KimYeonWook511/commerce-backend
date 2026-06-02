package com.commerce.payment.domain.repository;

import java.util.Optional;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentStatus;

public interface PaymentRepository {

	Payment save(Payment payment);

	Optional<Payment> findByMerchantPayKey(String merchantPayKey);

	boolean existsByMerchantPayKeyAndStatus(String merchantPayKey, PaymentStatus status);
}
