package com.commerce.payment.infrastructure;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.commerce.payment.domain.Payment;

public interface JpaPaymentRepository extends JpaRepository<Payment, Long> {

	Optional<Payment> findByMerchantPayKey(String merchantPayKey);
}
