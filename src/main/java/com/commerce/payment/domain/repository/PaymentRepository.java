package com.commerce.payment.domain.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Pageable;

import com.commerce.payment.domain.Payment;

/**
 * 저장 메서드가 셋인 것은 부르는 쪽이 무엇에 기대는지가 다르기 때문이다. 구현은 같지만 이름 말고는
 * 그 자리가 무엇에 기대는지 드러나지 않아, 평범한 저장으로 바꾸는 순간 조용히 깨진다.
 */
public interface PaymentRepository {

	/** 아무것도 기대하지 않는 저장. 충돌을 그대로 전파해 밖으로 내보내는 경로가 쓴다 */
	Payment save(Payment payment);

	/** 낙관 락 충돌을 이 호출 안에서 확정한다. 충돌을 잡아 물러나야 하는 경로가 쓴다 */
	Payment saveChecked(Payment payment);

	/**
	 * 이 저장이 뒤 저장보다 먼저 DB에 나가는 것에 기댄다. 한 트랜잭션에서 앞 결제의 슬롯을 비우고
	 * 새 결제에 같은 주문을 심을 때, 비우는 갱신이 삽입보다 먼저 나가야 유일 제약에 안 걸린다 —
	 * 코드 줄 순서로는 보장되지 않는다.
	 */
	Payment saveFlushed(Payment payment);

	Optional<Payment> findById(Long id);

	/** 그 주문의 활성 슬롯을 쥐고 있는 결제 */
	Optional<Payment> findActiveByOrderId(Long orderId);

	/**
	 * 결제 시작 멱등키로 앞서 만든 결제를 찾는다. 유일 제약과 같은 범위로 좁혀야 하므로 회원이 이름에
	 * 든다 — 키만으로 찾으면 같은 문자열을 보낸 다른 회원에게 남의 결제 키와 금액이 그대로 나간다.
	 */
	Optional<Payment> findByMemberIdAndIdempotencyKey(Long memberId, String idempotencyKey);

	/** 회원 요청 경로가 쓰는 결제 키 조회. 소유 확인이 이 조회를 타고 흐르므로 회원이 이름에 든다 */
	Optional<Payment> findByPaymentKeyAndMemberId(String paymentKey, Long memberId);

	/**
	 * 회수 전용 결제 키 조회. 승인 응답의 결제 키가 어긋났을 때 그 주인의 결제 행을 찾는 자리이며,
	 * 정의상 남의 결제라 회원으로 좁힐 수 없다. 회원 요청 경로가 이것을 그대로 쓰면 소유 확인이 사라진다.
	 */
	Optional<Payment> findByPaymentKey(String paymentKey);

	/** 그 주문에 승인 결과를 모르는 결제가 있나 */
	boolean existsUnknownByOrderId(Long orderId);

	/** 그 회원의 주문에서 성공한 결제 */
	Optional<Payment> findSucceededByMemberIdAndOrderId(Long memberId, Long orderId);

	/** 주어진 주문들 중 활성 슬롯을 쥔 결제가 있는 것. 배치가 부르는 자리라 회원으로 좁히지 않는다 */
	Set<Long> findOrderIdsHoldingActiveSlot(Collection<Long> orderIds);

	/**
	 * 승인을 호출하고 응답을 기다리는 결제 중 대사 대상.
	 * 회차별로 나눠 고르고 임계 시각은 코드가 간격표에서 계산해 넘긴다 — 간격 계산을 조회에 넣으면
	 * 판단이 인프라로 새고 인덱스도 범위로 좁히지 못한다. 마지막 회차는 그 이상을 전부 받아 회수가
	 * 멈추지 않게 한다.
	 */
	List<Payment> findInProgressReconcileTargets(
		LocalDateTime requestedBefore,
		int minReconcileCount,
		int maxReconcileCount,
		LocalDateTime reconciledBefore,
		Pageable pageable
	);

	/** 결과를 모르는 결제 중 대사 대상. 빨리 읽어 확정해야 하므로 대사 유예가 없다 */
	List<Payment> findUnknownReconcileTargets(
		int minReconcileCount,
		int maxReconcileCount,
		LocalDateTime reconciledBefore,
		Pageable pageable
	);

	/** 통지 대상. 승급 기준이 만들어진 시각 하나라 상태를 묶어 고른다 */
	List<Payment> findNotifyTargets(
		LocalDateTime createdBefore,
		LocalDateTime notifiedBefore,
		Pageable pageable
	);

	/** 만료 대상. 승인을 아직 부르지 않은 채 방치된 결제다 */
	List<Payment> findExpireTargets(LocalDateTime createdBefore, Pageable pageable);
}
