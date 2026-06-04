package com.commerce.payment.infrastructure;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.PaymentReservationStatus;

public interface JpaPaymentReservationRepository extends JpaRepository<PaymentReservation, Long> {

	Optional<PaymentReservation> findByMerchantPayKey(String merchantPayKey);

	@Query("SELECT r FROM PaymentReservation r WHERE r.orderId = :orderId AND r.memberId = :memberId AND r.provider = :provider AND r.amount = :amount AND r.status = :status AND r.expiresAt > :now")
	Optional<PaymentReservation> findReusable(
		@Param("orderId") Long orderId,
		@Param("memberId") Long memberId,
		@Param("provider") PaymentProvider provider,
		@Param("amount") int amount,
		@Param("status") PaymentReservationStatus status,
		@Param("now") LocalDateTime now
	);
}
