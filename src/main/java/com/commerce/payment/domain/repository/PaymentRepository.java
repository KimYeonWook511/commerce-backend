package com.commerce.payment.domain.repository;

import java.util.Optional;

import com.commerce.payment.domain.Payment;

public interface PaymentRepository {

	Payment save(Payment payment);

	Optional<Payment> findByMerchantPayKey(String merchantPayKey);
}
