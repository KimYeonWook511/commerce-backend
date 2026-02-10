package com.commerce.payment.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentReasonCode;
import com.commerce.payment.domain.PaymentStatus;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

	Optional<Payment> findByOrderId(Long orderId);

	Optional<Payment> findByMerchantPayKey(String merchantPayKey);

	@Query("""
		select p
		from Payment p
		join fetch p.order o
		where p.merchantPayKey = :merchantPayKey
		""")
	Optional<Payment> findByMerchantPayKeyWithOrder(@Param("merchantPayKey") String merchantPayKey);

	Optional<Payment> findByPgPaymentId(String pgPaymentId);

	@Query("""
		select p
		from Payment p
		join fetch p.order o
		where p.pgPaymentId = :pgPaymentId
		""")
	Optional<Payment> findByPgPaymentIdWithOrder(@Param("pgPaymentId") String pgPaymentId);

	@Query("""
		select p
		from Payment p
		join p.order o
		join o.member m
		where p.merchantPayKey = :merchantPayKey
		and m.id = :memberId
		""")
	Optional<Payment> findByMerchantPayKeyAndMemberId(
		@Param("merchantPayKey") String merchantPayKey,
		@Param("memberId") Long memberId
	);

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

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		update Payment p
		set p.status = :next,
			p.pgPaymentId = :pgPaymentId,
			p.reasonCode = :reasonCode,
			p.reasonDetail = :reasonDetail
		where p.merchantPayKey = :merchantPayKey
		and p.status = :expected
		""")
	int updateToCancelPending(
		@Param("merchantPayKey") String merchantPayKey,
		@Param("expected") PaymentStatus expected,
		@Param("next") PaymentStatus next,
		@Param("pgPaymentId") String pgPaymentId,
		@Param("reasonCode") PaymentReasonCode reasonCode,
		@Param("reasonDetail") String reasonDetail
	);
}
