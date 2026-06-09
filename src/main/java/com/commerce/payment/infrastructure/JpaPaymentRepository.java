package com.commerce.payment.infrastructure;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

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

	@Query("""
		SELECT p FROM Payment p
		WHERE p.type = 'APPROVE'
		  AND (
		    (p.status = 'UNKNOWN'    AND p.respondedAt < :staleCutoff AND p.respondedAt > :escalationCutoff)
		    OR
		    (p.status = 'REQUESTED' AND p.createdAt   < :staleCutoff AND p.createdAt   > :escalationCutoff)
		  )
		ORDER BY p.id ASC
		""")
	List<Payment> findStaleApprovePaymentsForReconciliation(
		@Param("staleCutoff") LocalDateTime staleCutoff,
		@Param("escalationCutoff") LocalDateTime escalationCutoff,
		Pageable pageable
	);

	@Query("""
		SELECT DISTINCT p.orderId FROM Payment p
		WHERE p.type = 'APPROVE'
		  AND p.status = 'UNKNOWN'
		  AND p.orderId IN :orderIds
		""")
	List<Long> findOrderIdsWithBlockingPaymentIn(@Param("orderIds") Collection<Long> orderIds);
}
