package com.commerce.payment.infrastructure;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

	// escalation 후보: 대사 스캔 윈도우(1분~6시간) 밖에 있고 escalatedAt IS NULL인 6시간 초과 UNKNOWN/REQUESTED APPROVE.
	@Query("""
		SELECT p FROM Payment p
		WHERE p.type = 'APPROVE'
		  AND p.escalatedAt IS NULL
		  AND (
		    (p.status = 'UNKNOWN'    AND p.respondedAt < :escalationCutoff)
		    OR
		    (p.status = 'REQUESTED' AND p.createdAt   < :escalationCutoff)
		  )
		ORDER BY p.id ASC
		""")
	List<Payment> findEscalationCandidates(
		@Param("escalationCutoff") LocalDateTime escalationCutoff,
		Pageable pageable
	);

	// 조건부 UPDATE: escalatedAt IS NULL AND status IN (UNKNOWN,REQUESTED)인 경우에만 escalatedAt 기록.
	// @Transactional로 트랜잭션이 없는 호출 컨텍스트(PaymentReconciliationService)에서도 독립 커밋된다.
	// 반환값(영향 행 수)이 1이면 이 호출이 escalation 주체, 0이면 이미 다른 주체가 처리(중복 통지 차단).
	@Transactional
	@Modifying
	@Query("""
		UPDATE Payment p SET p.escalatedAt = :now
		WHERE p.id = :id
		  AND p.escalatedAt IS NULL
		  AND p.status IN ('UNKNOWN', 'REQUESTED')
		""")
	int escalateIfPending(@Param("id") Long id, @Param("now") LocalDateTime now);
}
