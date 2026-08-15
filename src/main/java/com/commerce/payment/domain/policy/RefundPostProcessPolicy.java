package com.commerce.payment.domain.policy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 환불의 후처리를 언제 하는지 정하는 정책. 시각만 받아 판정하고 아무것도 실행하지 않는다.
 *
 * <p>결제와 정책을 나눠 갖는다. 각자 자기 상태 집합만 알고, 대사 유예도 값이 다르다.
 *
 * <p>만료 임계가 없다. 환불에는 "아예 안 부른 채 방치되어 종결시킬 것"이 없다 — 부를 준비 상태는 발송
 * 배치가 시간 조건 없이 집어 가므로 방치되지 않는다.
 *
 * <p>컨테이너 등록 애노테이션을 두지 않는 이유는 결제 정책과 같다. 아래 값들이 운영 설정에서 오는데
 * 애노테이션을 붙이면 설정을 읽는 일까지 도메인이 하게 된다.
 */
public class RefundPostProcessPolicy {

	/**
	 * 환불 호출을 부른 지 이만큼 지나야 대사가 집는다.
	 *
	 * <p>결제보다 길다. 겹쳤을 때 치르는 대가가 달라서다 — 환불은 돈이 나가는 호출이라 겹쳐 전송되면
	 * 같은 취소가 두 번 나가지만, 결제는 재요청이 겹쳐도 결제사가 "이미 진행 중"으로 막는다.
	 */
	private final Duration reconcileGrace;

	/** 회차별로 다시 집는 간격. 결제와 같은 표를 쓴다 */
	private final ReconcileSchedule reconcileSchedule;

	/** 만들어진 지 이만큼 지나도록 결과가 안 나면 운영자에게 알린다 */
	private final Duration notifyEscalation;

	/** 알린 뒤 다시 알리기까지의 간격 */
	private final Duration notifyInterval;

	public RefundPostProcessPolicy(
		Duration reconcileGrace,
		List<Duration> reconcileIntervals,
		Duration notifyEscalation,
		Duration notifyInterval
	) {
		this.reconcileGrace = reconcileGrace;
		this.reconcileSchedule = new ReconcileSchedule(reconcileIntervals);
		this.notifyEscalation = notifyEscalation;
		this.notifyInterval = notifyInterval;
	}

	/**
	 * 환불을 부른 지 대사 유예가 지난 건을 가르는 임계 시각. 이 값보다 앞서 부른 것만 집는다.
	 *
	 * <p>이 유예는 우리가 그 호출을 붙들고 있는 최대 시간보다 길어야 한다. 짧으면 요청 흐름이 아직 응답을
	 * 기다리는 중인 건을 대사가 집어 같은 환불을 둘이 동시에 보낸다.
	 *
	 * <p>재는 기준이 마지막으로 부른 시각이다. 행이 만들어진 시각으로 재면 다시 보낸 건이 보내는 즉시
	 * 지연을 넘긴 것으로 읽혀, 주기마다 같은 환불이 계속 나간다.
	 */
	public LocalDateTime requestedBefore(LocalDateTime at) {
		return at.minus(reconcileGrace);
	}

	/** 회차별 대사 대상 조건. 결제와 같은 표를 쓰므로 계산도 그 표가 한다 */
	public List<ReconcileWindow> reconcileWindows(LocalDateTime at) {
		return reconcileSchedule.windows(at);
	}

	/** 만들어진 지 통지 승급 시간이 지난 건을 가르는 임계 시각 */
	public LocalDateTime createdBeforeForNotify(LocalDateTime at) {
		return at.minus(notifyEscalation);
	}

	/** 다시 알릴 때가 된 건을 가르는 임계 시각. 아직 알린 적이 없으면 그것만으로 대상이다 */
	public LocalDateTime notifiedBefore(LocalDateTime at) {
		return at.minus(notifyInterval);
	}
}
