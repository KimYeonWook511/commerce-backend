package com.commerce.payment.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundRequester;
import com.commerce.payment.domain.repository.ReconcileTarget;

public interface JpaRefundRepository extends JpaRepository<Refund, Long> {

	Optional<Refund> findByPaymentIdAndRequesterAndIdempotencyKey(
		Long paymentId,
		RefundRequester requester,
		String idempotencyKey
	);

	Optional<Refund> findByPaymentIdAndRequester(Long paymentId, RefundRequester requester);

	// 발송 대상에는 시간 조건이 없다. 이 상태로 오는 것은 한 번도 안 부른 건과 사람이 되살린 건뿐이라
	// 되풀이 나갈 자리가 없고, 재전송의 백오프는 대사 쪽에 있다.
	@Query("""
		SELECT r FROM Refund r
		WHERE r.status = 'READY'
		ORDER BY r.id ASC
		""")
	List<Refund> findDispatchTargets(Pageable pageable);

	// 대사 대상 조회 넷에는 정렬이 없다. 대상을 개수로 자르지 않아 어차피 다 처리하므로 정렬이 고르는
	// 것을 바꾸지 않고, 인덱스가 주는 순서(회차가 낮은 것 먼저, 같은 회차 안에서는 오래 안 본 것 먼저)가
	// 그대로 처리 순서로 쓸 만하다. 상한이 있는 발송·통지·만료 조회에는 정렬이 남는다 — 그쪽은 정렬이
	// 어느 건을 자를지 정한다.
	//
	// 집은 시각 조건은 회차 간격만 재는 것이 아니다. 한 주기가 창을 돌며 조회하는 사이 다른 주기가 그
	// 행을 집으면 회차가 올라 다음 창 조회에 다시 걸릴 수 있는데, 방금 찍힌 집은 시각이 그것을 막는다.
	// 간격표의 첫 값을 0에 가깝게 줄이면 이 방어가 사라진다.
	@Query("""
		SELECT new com.commerce.payment.domain.repository.ReconcileTarget(r.id, r.reconcileCount)
		FROM Refund r
		WHERE r.status = 'IN_PROGRESS'
		  AND r.reconcileCount >= :minReconcileCount
		  AND r.reconcileCount <= :maxReconcileCount
		  AND r.lastRequestedAt < :requestedBefore
		  AND (r.lastReconcileAt IS NULL OR r.lastReconcileAt < :reconciledBefore)
		""")
	List<ReconcileTarget> findInProgressReconcileTargets(
		@Param("requestedBefore") LocalDateTime requestedBefore,
		@Param("minReconcileCount") int minReconcileCount,
		@Param("maxReconcileCount") int maxReconcileCount,
		@Param("reconciledBefore") LocalDateTime reconciledBefore
	);

	@Query("""
		SELECT new com.commerce.payment.domain.repository.ReconcileTarget(r.id, r.reconcileCount)
		FROM Refund r
		WHERE r.status = 'UNKNOWN'
		  AND r.reconcileCount >= :minReconcileCount
		  AND r.reconcileCount <= :maxReconcileCount
		  AND (r.lastReconcileAt IS NULL OR r.lastReconcileAt < :reconciledBefore)
		""")
	List<ReconcileTarget> findUnknownReconcileTargets(
		@Param("minReconcileCount") int minReconcileCount,
		@Param("maxReconcileCount") int maxReconcileCount,
		@Param("reconciledBefore") LocalDateTime reconciledBefore
	);

	// 사람이 처리해야 하는 건은 승급을 기다리지 않는다. 그 상태 자체가 이미 조치할 것이 남았다는 뜻이고,
	// 사람이 돈을 돌려주면 상태가 바뀌어 저절로 빠진다.
	@Query("""
		SELECT r FROM Refund r
		WHERE (
		    (r.status IN ('IN_PROGRESS', 'UNKNOWN') AND r.createdAt < :createdBefore)
		    OR r.status = 'MANUAL_REVIEW'
		  )
		  AND (r.lastNotifyAt IS NULL OR r.lastNotifyAt < :notifiedBefore)
		ORDER BY r.id ASC
		""")
	List<Refund> findNotifyTargets(
		@Param("createdBefore") LocalDateTime createdBefore,
		@Param("notifiedBefore") LocalDateTime notifiedBefore,
		Pageable pageable
	);
}
