package com.commerce.payment.infrastructure;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.repository.PaymentReservationRepository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class PaymentReservationRepositoryAdapter implements PaymentReservationRepository {

	private final JpaPaymentReservationRepository jpaPaymentReservationRepository;

	@Override
	public PaymentReservation save(PaymentReservation reservation) {
		return jpaPaymentReservationRepository.saveAndFlush(reservation);
	}

	@Override
	public Optional<PaymentReservation> findByMerchantPayKey(String merchantPayKey) {
		return jpaPaymentReservationRepository.findByMerchantPayKey(merchantPayKey);
	}

	@Override
	public Optional<PaymentReservation> findReserved(Long orderId, PaymentProvider provider) {
		return jpaPaymentReservationRepository.findReserved(orderId, provider);
	}
}
