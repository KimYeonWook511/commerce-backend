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

	// REQUESTED는 UNKNOWN보다 늦은 하한(requestedStaleCutoff)을 쓴다. 정책 진입 지연(REQUESTED 15분 / UNKNOWN 1분)과
	// 스캔 하한을 일치시켜, 진입 지연 전 REQUESTED가 id ASC 첫 페이지를 차지하고 매 주기 버려져 뒤 후보가 고사하는 것을 막는다.
	@Query("""
		SELECT p FROM Payment p
		WHERE p.type = 'APPROVE'
		  AND (
		    (p.status = 'UNKNOWN'    AND p.respondedAt < :staleCutoff          AND p.respondedAt > :escalationCutoff)
		    OR
		    (p.status = 'REQUESTED' AND p.createdAt   < :requestedStaleCutoff AND p.createdAt   > :escalationCutoff)
		  )
		ORDER BY p.id ASC
		""")
	List<Payment> findStaleApprovePaymentsForReconciliation(
		@Param("staleCutoff") LocalDateTime staleCutoff,
		@Param("requestedStaleCutoff") LocalDateTime requestedStaleCutoff,
		@Param("escalationCutoff") LocalDateTime escalationCutoff,
		Pageable pageable
	);

	// UNKNOWN뿐 아니라 미확정 REQUESTED(승인 호출 후 결과 저장 전 중단되어 실제 과금됐을 수 있음)도
	// 만료 차단 대상에 포함해 만료-대사 경합을 막는다.
	@Query("""
		SELECT DISTINCT p.orderId FROM Payment p
		WHERE p.type = 'APPROVE'
		  AND p.status IN ('UNKNOWN', 'REQUESTED')
		  AND p.orderId IN :orderIds
		""")
	List<Long> findOrderIdsWithBlockingPaymentIn(@Param("orderIds") Collection<Long> orderIds);
}
