package com.commerce.payment.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.commerce.payment.domain.Payment;

public interface JpaPaymentRepository extends JpaRepository<Payment, Long> {

	Optional<Payment> findByActiveOrderKey(Long activeOrderKey);

	Optional<Payment> findByPaymentKeyAndMemberId(String paymentKey, Long memberId);

	Optional<Payment> findByPaymentKey(String paymentKey);

	@Query("""
		SELECT CASE WHEN COUNT(p) > 0 THEN TRUE ELSE FALSE END FROM Payment p
		WHERE p.orderId = :orderId
		  AND p.status = 'UNKNOWN'
		""")
	boolean existsUnknownByOrderId(@Param("orderId") Long orderId);

	@Query("""
		SELECT p FROM Payment p
		WHERE p.memberId = :memberId
		  AND p.orderId = :orderId
		  AND p.status = 'SUCCEEDED'
		""")
	Optional<Payment> findSucceededByMemberIdAndOrderId(
		@Param("memberId") Long memberId,
		@Param("orderId") Long orderId
	);

	// 활성 슬롯에는 살아 있는 결제만 주문 식별자를 담고 그 값이 유일하므로, 슬롯 값을 그대로 읽으면
	// 막힌 주문 목록이 된다. 중복 제거도 필요 없다.
	@Query("""
		SELECT p.activeOrderKey FROM Payment p
		WHERE p.activeOrderKey IN :orderIds
		""")
	List<Long> findActiveOrderKeysIn(@Param("orderIds") Collection<Long> orderIds);

	// 임계 시각과 회차 범위는 코드가 간격표에서 계산해 넘긴다. 그래야 조회에 등호와 부등호만 남아
	// (status, reconcile_count, last_reconcile_at) 인덱스가 그대로 먹는다.
	// 부른 지 대사 유예가 지난 건만 고른다 — 요청 흐름이 아직 응답을 기다리는 중일 수 있다.
	@Query("""
		SELECT p FROM Payment p
		WHERE p.status = 'IN_PROGRESS'
		  AND p.reconcileCount >= :minReconcileCount
		  AND p.reconcileCount <= :maxReconcileCount
		  AND p.lastRequestedAt < :requestedBefore
		  AND (p.lastReconcileAt IS NULL OR p.lastReconcileAt < :reconciledBefore)
		ORDER BY p.id ASC
		""")
	List<Payment> findInProgressReconcileTargets(
		@Param("requestedBefore") LocalDateTime requestedBefore,
		@Param("minReconcileCount") int minReconcileCount,
		@Param("maxReconcileCount") int maxReconcileCount,
		@Param("reconciledBefore") LocalDateTime reconciledBefore,
		Pageable pageable
	);

	// 결과를 모르는 건은 빨리 읽어 확정해야 하므로 대사 유예 없이 "아직 안 읽었다"가 곧 대상이다.
	@Query("""
		SELECT p FROM Payment p
		WHERE p.status = 'UNKNOWN'
		  AND p.reconcileCount >= :minReconcileCount
		  AND p.reconcileCount <= :maxReconcileCount
		  AND (p.lastReconcileAt IS NULL OR p.lastReconcileAt < :reconciledBefore)
		ORDER BY p.id ASC
		""")
	List<Payment> findUnknownReconcileTargets(
		@Param("minReconcileCount") int minReconcileCount,
		@Param("maxReconcileCount") int maxReconcileCount,
		@Param("reconciledBefore") LocalDateTime reconciledBefore,
		Pageable pageable
	);

	@Query("""
		SELECT p FROM Payment p
		WHERE p.status IN ('IN_PROGRESS', 'UNKNOWN')
		  AND p.createdAt < :createdBefore
		  AND (p.lastNotifyAt IS NULL OR p.lastNotifyAt < :notifiedBefore)
		ORDER BY p.id ASC
		""")
	List<Payment> findNotifyTargets(
		@Param("createdBefore") LocalDateTime createdBefore,
		@Param("notifiedBefore") LocalDateTime notifiedBefore,
		Pageable pageable
	);

	@Query("""
		SELECT p FROM Payment p
		WHERE p.status = 'READY'
		  AND p.createdAt < :createdBefore
		ORDER BY p.id ASC
		""")
	List<Payment> findExpireTargets(@Param("createdBefore") LocalDateTime createdBefore, Pageable pageable);
}
