package com.commerce.payment.infrastructure;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class PaymentRepositoryAdapter implements PaymentRepository {

	private final JpaPaymentRepository jpaPaymentRepository;

	@Override
	public Payment save(Payment payment) {
		return jpaPaymentRepository.saveAndFlush(payment);
	}

	@Override
	public Optional<Payment> findByMerchantPayKey(String merchantPayKey) {
		return jpaPaymentRepository.findByMerchantPayKey(merchantPayKey);
	}
}
