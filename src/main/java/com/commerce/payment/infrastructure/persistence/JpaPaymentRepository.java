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
import com.commerce.payment.domain.repository.ReconcileTarget;

public interface JpaPaymentRepository extends JpaRepository<Payment, Long> {

	Optional<Payment> findByActiveOrderKey(Long activeOrderKey);

	Optional<Payment> findByMemberIdAndIdempotencyKey(Long memberId, String idempotencyKey);

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
	//
	// 대사 대상 조회 넷에는 정렬이 없다. 대상을 개수로 자르지 않아 어차피 다 처리하므로 정렬이 고르는
	// 것을 바꾸지 않고, 인덱스가 주는 순서(회차가 낮은 것 먼저, 같은 회차 안에서는 오래 안 본 것 먼저)가
	// 그대로 처리 순서로 쓸 만하다. 상한이 있는 발송·통지·만료 조회에는 정렬이 남는다 — 그쪽은 정렬이
	// 어느 건을 자를지 정한다.
	//
	// 집은 시각 조건은 회차 간격만 재는 것이 아니다. 한 주기가 창을 돌며 조회하는 사이 다른 주기가 그
	// 행을 집으면 회차가 올라 다음 창 조회에 다시 걸릴 수 있는데, 방금 찍힌 집은 시각이 그것을 막는다.
	// 간격표의 첫 값을 0에 가깝게 줄이면 이 방어가 사라진다.
	@Query("""
		SELECT new com.commerce.payment.domain.repository.ReconcileTarget(p.id, p.reconcileCount)
		FROM Payment p
		WHERE p.status = 'IN_PROGRESS'
		  AND p.reconcileCount >= :minReconcileCount
		  AND p.reconcileCount <= :maxReconcileCount
		  AND p.lastRequestedAt < :requestedBefore
		  AND (p.lastReconcileAt IS NULL OR p.lastReconcileAt < :reconciledBefore)
		""")
	List<ReconcileTarget> findInProgressReconcileTargets(
		@Param("requestedBefore") LocalDateTime requestedBefore,
		@Param("minReconcileCount") int minReconcileCount,
		@Param("maxReconcileCount") int maxReconcileCount,
		@Param("reconciledBefore") LocalDateTime reconciledBefore
	);

	// 결과를 모르는 건은 빨리 읽어 확정해야 하므로 대사 유예 없이 "아직 안 읽었다"가 곧 대상이다.
	@Query("""
		SELECT new com.commerce.payment.domain.repository.ReconcileTarget(p.id, p.reconcileCount)
		FROM Payment p
		WHERE p.status = 'UNKNOWN'
		  AND p.reconcileCount >= :minReconcileCount
		  AND p.reconcileCount <= :maxReconcileCount
		  AND (p.lastReconcileAt IS NULL OR p.lastReconcileAt < :reconciledBefore)
		""")
	List<ReconcileTarget> findUnknownReconcileTargets(
		@Param("minReconcileCount") int minReconcileCount,
		@Param("maxReconcileCount") int maxReconcileCount,
		@Param("reconciledBefore") LocalDateTime reconciledBefore
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
