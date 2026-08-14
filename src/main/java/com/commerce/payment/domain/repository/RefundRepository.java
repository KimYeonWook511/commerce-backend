package com.commerce.payment.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;

import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundRequester;

/**
 * 합계 조회를 두지 않는다. 한도 판정은 결제 행의 승인 금액과 누적 환불액만 읽으므로 결제를 로드하는
 * 것으로 끝나고, 값을 두 곳에서 읽으면 어긋났을 때 어느 쪽을 믿을지를 정해야 한다.
 */
public interface RefundRepository {

	/** 아무것도 기대하지 않는 저장 */
	Refund save(Refund refund);

	/** 낙관 락 충돌을 이 호출 안에서 확정한다 */
	Refund saveChecked(Refund refund);

	Optional<Refund> findById(Long id);

	/**
	 * 그 결제에서 요청자와 요청 키로 기존 사건을 찾는다. 유일 제약이 그 셋으로 묶여 있어 인덱스가
	 * 그대로 먹고, 회원 요청이 시스템 환불을 집어 오는 일도 없다.
	 */
	Optional<Refund> findByPaymentIdAndRequesterAndIdempotencyKey(
		Long paymentId,
		RefundRequester requester,
		String idempotencyKey
	);

	/** 그 결제의 시스템 환불. 승인 반려는 결제당 한 건이라 이미 있으면 그것을 돌려준다 */
	Optional<Refund> findSystemRefundByPaymentId(Long paymentId);

	/**
	 * 아직 한 번도 안 나갔거나 사람이 되살려 보낼 차례인 환불.
	 * 시간 조건이 없다 — 회원 돈이라 늦출 이유가 없고, 재전송은 이 상태를 지나가지 않아 폭주하지 않는다.
	 */
	List<Refund> findDispatchTargets(Pageable pageable);

	/** 결제사를 부르고 응답을 기다리는 환불 중 대사 대상. 회차별로 나눠 고른다 */
	List<Refund> findInProgressReconcileTargets(
		LocalDateTime requestedBefore,
		int minReconcileCount,
		int maxReconcileCount,
		LocalDateTime reconciledBefore,
		Pageable pageable
	);

	/** 결과를 모르는 환불 중 대사 대상 */
	List<Refund> findUnknownReconcileTargets(
		int minReconcileCount,
		int maxReconcileCount,
		LocalDateTime reconciledBefore,
		Pageable pageable
	);

	/**
	 * 통지 대상. 사람이 처리해야 하는 건은 승급을 기다리지 않고 바로 대상이 된다 — 조치할 것이 남은
	 * 건이고, 알림이 계속 온다는 것이 곧 아직 아무도 처리하지 않았다는 사실이다.
	 */
	List<Refund> findNotifyTargets(
		LocalDateTime createdBefore,
		LocalDateTime notifiedBefore,
		Pageable pageable
	);
}
