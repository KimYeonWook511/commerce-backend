package com.commerce.payment.domain.policy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 결제의 후처리를 언제 하는지 정하는 정책. 시각만 받아 판정하고 아무것도 실행하지 않는다.
 *
 * <p>대사·통지·만료가 쓰는 시간 값을 이 한 자리가 갖는다. 배치가 같은 값을 따로 들면 한쪽만 바뀌어
 * 갈라진다.
 *
 * <p>컨테이너 등록 애노테이션을 두지 않는다. 아래 값들이 운영 설정에서 오는데 애노테이션을 붙이면
 * 설정을 읽는 일까지 도메인이 하게 되고, 값을 바꿀 때 도메인을 건드리게 된다. 등록하는 자리가 값을
 * 읽어 넘기면 이 클래스는 받은 값으로 판단만 한다.
 */
public class PaymentPostProcessPolicy {

	/** 승인 호출을 부른 지 이만큼 지나야 대사가 집는다. 그 전에는 요청 흐름이 아직 응답을 기다리는 중일 수 있다 */
	private final Duration reconcileGrace;

	/** 회차별로 다시 집는 간격. 초반을 촘촘하게 두어 정상 건을 빨리 확정하고, 마지막 값이 그 뒤로 계속 쓰인다 */
	private final ReconcileSchedule reconcileSchedule;

	/** 만들어진 지 이만큼 지나도록 결과가 안 나면 운영자에게 알린다 */
	private final Duration notifyEscalation;

	/** 알린 뒤 다시 알리기까지의 간격 */
	private final Duration notifyInterval;

	/** 승인을 한 번도 부르지 않은 결제를 종결하기까지 */
	private final Duration expireThreshold;

	/**
	 * 한 회차의 대사 대상이 이 수를 넘으면 밀린 것으로 보고 알린다. 자르는 값이 아니라 알리는 값이다 —
	 * 상한으로 조용히 자르면 얼마나 밀렸는지가 아무 데도 남지 않는다.
	 */
	private final int reconcileBacklogThreshold;

	public PaymentPostProcessPolicy(
		Duration reconcileGrace,
		List<Duration> reconcileIntervals,
		Duration notifyEscalation,
		Duration notifyInterval,
		Duration expireThreshold,
		int reconcileBacklogThreshold
	) {
		if (reconcileBacklogThreshold <= 0) {
			// 0이나 음수면 대상이 하나만 있어도 밀린 것이 되어 주기마다 알린다.
			throw new IllegalArgumentException("대사 밀림 임계는 1 이상이어야 한다");
		}
		this.reconcileGrace = reconcileGrace;
		this.reconcileSchedule = new ReconcileSchedule(reconcileIntervals);
		this.notifyEscalation = notifyEscalation;
		this.notifyInterval = notifyInterval;
		this.expireThreshold = expireThreshold;
		this.reconcileBacklogThreshold = reconcileBacklogThreshold;
	}

	/**
	 * 승인을 부른 지 대사 유예가 지난 건을 가르는 임계 시각. 이 값보다 앞서 부른 것만 집는다.
	 *
	 * <p>이 유예는 승인 호출의 읽기 제한 시간보다 길어야 한다. 짧으면 요청 흐름이 아직 응답을 기다리는
	 * 중인 건을 대사가 집어 같은 결제에 승인이 겹쳐 나간다.
	 */
	public LocalDateTime requestedBefore(LocalDateTime at) {
		return at.minus(reconcileGrace);
	}

	/** 회차별 대사 대상 조건. 환불과 같은 표를 쓰므로 계산도 그 표가 한다 */
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

	/** 방치된 결제를 종결할 때가 됐는지 가르는 임계 시각 */
	public LocalDateTime createdBeforeForExpire(LocalDateTime at) {
		return at.minus(expireThreshold);
	}

	/** 이번 회차의 대상 수가 밀렸다고 볼 만한가 */
	public boolean isReconcileBacklogged(int targetCount) {
		return targetCount > reconcileBacklogThreshold;
	}

	/** 밀렸다고 보기로 한 값. 알림에 함께 실어 사람이 기준을 알 수 있게 한다 */
	public int reconcileBacklogThreshold() {
		return reconcileBacklogThreshold;
	}
}
