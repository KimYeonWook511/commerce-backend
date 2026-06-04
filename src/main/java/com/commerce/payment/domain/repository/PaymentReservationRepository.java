package com.commerce.payment.domain.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;

public interface PaymentReservationRepository {

	PaymentReservation save(PaymentReservation reservation);

	Optional<PaymentReservation> findByMerchantPayKey(String merchantPayKey);

	Optional<PaymentReservation> findReusable(Long orderId, Long memberId, PaymentProvider provider, int amount, LocalDateTime now);
}
