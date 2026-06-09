package com.commerce.payment.infrastructure;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.PaymentType;

public interface JpaPaymentRepository extends JpaRepository<Payment, Long> {

	Optional<Payment> findByMerchantPayKeyAndProviderAndPgPaymentIdAndType(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		PaymentType type
	);

	Optional<Payment> findByMerchantPayKeyAndTypeAndStatus(
		String merchantPayKey,
		PaymentType type,
		PaymentStatus status
	);

	boolean existsByOrderIdAndTypeAndStatus(Long orderId, PaymentType type, PaymentStatus status);

	boolean existsByOrderIdAndTypeAndStatusIn(Long orderId, PaymentType type, Collection<PaymentStatus> statuses);

	@Query("""
		SELECT p FROM Payment p
		WHERE p.type = 'APPROVE'
		  AND (
		    (p.status = 'UNKNOWN'    AND p.respondedAt < :cutoff)
		    OR
		    (p.status = 'REQUESTED' AND p.createdAt   < :cutoff)
		  )
		ORDER BY p.id ASC
		""")
	List<Payment> findStaleApprovePaymentsForReconciliation(
		@Param("cutoff") LocalDateTime cutoff,
		Pageable pageable
	);

	@Query("""
		SELECT DISTINCT p.orderId FROM Payment p
		WHERE p.type = 'APPROVE'
		  AND p.status IN ('UNKNOWN', 'MANUAL_REVIEW')
		  AND p.orderId IN :orderIds
		""")
	List<Long> findOrderIdsWithBlockingPaymentIn(@Param("orderIds") Collection<Long> orderIds);
}
