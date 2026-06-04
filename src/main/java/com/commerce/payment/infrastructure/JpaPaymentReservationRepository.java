package com.commerce.payment.infrastructure;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;

public interface JpaPaymentReservationRepository extends JpaRepository<PaymentReservation, Long> {

	Optional<PaymentReservation> findByMerchantPayKey(String merchantPayKey);

	@Query("""
		select pr from PaymentReservation pr
		where pr.orderId = :orderId
		  and pr.provider = :provider
		  and pr.status = com.commerce.payment.domain.PaymentReservationStatus.RESERVED
		""")
	Optional<PaymentReservation> findReserved(
		@Param("orderId") Long orderId,
		@Param("provider") PaymentProvider provider
	);
}
