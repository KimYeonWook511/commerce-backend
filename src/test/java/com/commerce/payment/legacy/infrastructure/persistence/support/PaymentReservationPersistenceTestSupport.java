package com.commerce.payment.legacy.infrastructure.persistence.support;

import java.util.Optional;

import org.springframework.boot.test.context.TestComponent;

import com.commerce.payment.legacy.domain.PaymentReservation;
import com.commerce.payment.legacy.infrastructure.persistence.JpaPaymentReservationRepository;
import com.commerce.support.CleanupOrder;
import com.commerce.support.PersistenceTestSupport;

import lombok.RequiredArgsConstructor;

@TestComponent
@RequiredArgsConstructor
public class PaymentReservationPersistenceTestSupport implements PersistenceTestSupport {

	private final JpaPaymentReservationRepository reservationRepository;

	@Override
	public CleanupOrder cleanupOrder() {
		return CleanupOrder.LEGACY_PAYMENT_RESERVATION;
	}

	@Override
	public void deleteAllInBatch() {
		reservationRepository.deleteAllInBatch();
	}

	public PaymentReservation save(PaymentReservation reservation) {
		return reservationRepository.save(reservation);
	}

	public Optional<PaymentReservation> findByMerchantPayKey(String merchantPayKey) {
		return reservationRepository.findByMerchantPayKey(merchantPayKey);
	}

	public long count() {
		return reservationRepository.count();
	}
}
