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

public interface JpaRefundRepository extends JpaRepository<Refund, Long> {

	Optional<Refund> findByPaymentIdAndRequesterAndIdempotencyKey(
		Long paymentId,
		RefundRequester requester,
		String idempotencyKey
	);

	Optional<Refund> findByPaymentIdAndRequester(Long paymentId, RefundRequester requester);

	@Query("""
		SELECT r FROM Refund r
		WHERE r.paymentId = :paymentId
		  AND r.status IN ('IN_PROGRESS', 'UNKNOWN')
		ORDER BY r.id ASC
		""")
	List<Refund> findUnsettledByPaymentId(@Param("paymentId") Long paymentId);

	// 발송 대상에는 시간 조건이 없다. 이 상태로 오는 것은 한 번도 안 부른 건과 사람이 되살린 건뿐이라
	// 되풀이 나갈 자리가 없고, 재전송의 백오프는 대사 쪽에 있다.
	@Query("""
		SELECT r FROM Refund r
		WHERE r.status = 'REQUESTED'
		ORDER BY r.id ASC
		""")
	List<Refund> findDispatchTargets(Pageable pageable);

	@Query("""
		SELECT r FROM Refund r
		WHERE r.status = 'IN_PROGRESS'
		  AND r.reconcileCount >= :minReconcileCount
		  AND r.reconcileCount <= :maxReconcileCount
		  AND r.lastRequestedAt < :requestedBefore
		  AND (r.lastReconcileAt IS NULL OR r.lastReconcileAt < :reconciledBefore)
		ORDER BY r.id ASC
		""")
	List<Refund> findInProgressReconcileTargets(
		@Param("requestedBefore") LocalDateTime requestedBefore,
		@Param("minReconcileCount") int minReconcileCount,
		@Param("maxReconcileCount") int maxReconcileCount,
		@Param("reconciledBefore") LocalDateTime reconciledBefore,
		Pageable pageable
	);

	@Query("""
		SELECT r FROM Refund r
		WHERE r.status = 'UNKNOWN'
		  AND r.reconcileCount >= :minReconcileCount
		  AND r.reconcileCount <= :maxReconcileCount
		  AND (r.lastReconcileAt IS NULL OR r.lastReconcileAt < :reconciledBefore)
		ORDER BY r.id ASC
		""")
	List<Refund> findUnknownReconcileTargets(
		@Param("minReconcileCount") int minReconcileCount,
		@Param("maxReconcileCount") int maxReconcileCount,
		@Param("reconciledBefore") LocalDateTime reconciledBefore,
		Pageable pageable
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
