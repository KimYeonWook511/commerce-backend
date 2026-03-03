package com.commerce.payment.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.commerce.payment.domain.PaymentAttempt;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {

	Optional<PaymentAttempt> findByMerchantPayKeyAndPaymentId(String merchantPayKey, String paymentId);

	Optional<PaymentAttempt> findTopByMerchantPayKeyOrderByIdDesc(String merchantPayKey);
}
