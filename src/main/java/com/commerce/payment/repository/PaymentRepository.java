package com.commerce.payment.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentStatus;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

	Optional<Payment> findByOrderId(Long orderId);

	Optional<Payment> findByMerchantPayKey(String merchantPayKey);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		update Payment p
		set p.status = :next
		where p.merchantPayKey = :merchantPayKey
		and p.status = :expected
		""")
	int updateStatusIfMatches(
		@Param("merchantPayKey") String merchantPayKey,
		@Param("expected") PaymentStatus expected,
		@Param("next") PaymentStatus next
	);
}
