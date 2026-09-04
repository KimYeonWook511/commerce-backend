package com.commerce.payment.application.usecase;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.commerce.payment.application.port.NotificationPort;
import com.commerce.payment.application.port.PaymentGatewayPort;
import com.commerce.payment.application.port.dto.PgCallSource;
import com.commerce.payment.application.port.dto.PgHistoryEntry;
import com.commerce.payment.application.port.dto.PgHistoryResult;
import com.commerce.payment.application.port.dto.PgHistoryScope;
import com.commerce.payment.application.port.dto.PgOutcome;
import com.commerce.payment.application.service.RefundService;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundReviewCode;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.domain.policy.ReconcileWindow;
import com.commerce.payment.domain.policy.RefundPostProcessPolicy;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.repository.ReconcileTarget;
import com.commerce.payment.domain.repository.RefundRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결과를 모르는 환불을 이력으로 확정한다. 주기 실행으로만 돌고 밖에서 부를 수 있는 경로가 없다.
 *
 * <p>한 주기에 이력을 한 번 읽는다. 우리 시도가 완료로 있으면 그대로 확정하고, 없거나 실패로만 있으면
 * <b>그 자리에서</b> 같은 키로 다시 보낸다. 읽기와 전송을 다른 주기로 나누면 그 사이 결제사가 이력에
 * 반영해 확인이 낡는다 — 없다고 읽었는데 보내는 시점에는 이미 나간 돈이 있을 수 있다.
 *
 * <p>정당성을 다시 확인하지 않는다. 환불 행은 그것을 정당화하는 상태 변경과 같은 트랜잭션에서만
 * 만들어지므로, 행이 있다는 것 자체가 이미 정당한 흐름을 지났다는 뜻이다. 그래서 다른 도메인에 묻지
 * 않는다.
 *
 * <p>회수를 멈추지 않는다. 멈추면 돈이 안 돌아간 채 남고 그 금액만큼 한도가 계속 막힌다. 상한이 있는
 * 것은 다시 집는 간격뿐이다.
 *
 * <p>확정만 결제 행을 함께 바꾼다. 그 자리에서 실제로 돌아간 금액이 오르기 때문이며, 확정은 환불 건당
 * 한 번이라 대사가 그냥 한 바퀴 도는 것으로는 결제 버전이 오르지 않는다. 나머지 전이는 환불만 바꾼다 —
 * 매 주기 결제 버전을 올리면 그동안 회원의 환불 요청이 낙관 락 충돌로 밀린다. 결제를 읽는 것은
 * 결제사를 부르는 데 필요한 값이 그 행에 있어서다.
 *
 * <p>트랜잭션을 열지 않는다. 건마다 단위작업이 따로 커밋되어 한 건이 실패해도 나머지가 돌고, 낙관 락
 * 충돌도 건별로 걸린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconcileRefundUseCase {

	private static final String BACKLOG_SUBJECT = "환불 대사";

	/**
	 * 마지막으로 밀렸다고 알린 시각. 밀린 상태가 이어지는 동안 주기마다 알리면 통지 수단을 갈아끼우는
	 * 순간 같은 사실이 분마다 쏟아진다.
	 *
	 * <p>알린 사실을 행이 아니라 이 자리에 든다 — 밀림은 환불 하나가 아니라 그 주기 전체의 성질이라
	 * 남길 행이 없다. 그래서 인스턴스마다 따로 세고, 여럿이 돌면 그 수만큼 알림이 나간다. 통지 자체가
	 * 사람을 부르는 신호라 그 정도 중복은 감수한다.
	 */
	private volatile LocalDateTime lastBacklogAlertAt;

	private final RefundRepository refundRepository;
	private final PaymentRepository paymentRepository;
	private final PaymentGatewayPort paymentGatewayPort;
	private final RefundService refundService;
	private final ExecuteRefundUseCase executeRefundUseCase;
	private final NotificationPort notificationPort;
	private final RefundPostProcessPolicy policy;

	/**
	 * 집은 대상을 개수로 자르지 않고 다 처리한다. 자르면 남은 건이 다음 주기로 밀릴 뿐 총 처리 시간은
	 * 줄지 않고, 그 주기는 다음 정각에야 오므로 잘게 쪼갤수록 마지막 건이 끝나는 시각이 뒤로 밀린다.
	 * 대신 대상이 임계를 넘으면 알린다 — 밀렸다는 사실이 조용히 잘려 사라지지 않게 한다.
	 */
	public void reconcile() {
		List<ReconcileTarget> targets = findTargets(LocalDateTime.now());
		if (targets.isEmpty()) {
			return;
		}

		log.info("환불 대사 시작 targets={}", targets.size());
		alertIfBacklogged(targets.size(), LocalDateTime.now());
		for (ReconcileTarget target : targets) {
			try {
				reconcileOne(target);
			} catch (Exception ex) {
				// 집기 자체가 깨진 자리다. 그 행을 읽어 온 적이 없어 남길 것이 식별자뿐이며, 집은 뒤의
				// 실패는 행을 손에 들고 더 자세히 남긴다.
				log.error("환불 대사가 집지 못했다 refundId={}", target.id(), ex);
			}
		}
	}

	/**
	 * 밀렸다는 것을 알린다. 한 번 알린 뒤에는 통지 간격이 지나야 다시 알린다 — 밀린 상태는 몇 주기를
	 * 이어가는데 주기마다 알리면 같은 사실이 분마다 쏟아진다. 그 간격은 미해결 건 통지가 쓰는 값과 같다.
	 *
	 * <p>알림이 실패해도 이 주기를 끝내지 않는다 — 전파하면 밀렸을 때 알리려고 둔 것이 밀렸을 때 회수를
	 * 통째로 멈추고, 대상이 그대로라 다음 주기도 같은 자리에서 죽는다. 실패한 알림의 시각은 남기지
	 * 않는다. 남기면 못 보낸 것이 보낸 것으로 세어져 다음 주기가 조용해진다.
	 */
	private void alertIfBacklogged(int targetCount, LocalDateTime now) {
		if (!policy.isReconcileBacklogged(targetCount) || isWithinAlertInterval(now)) {
			return;
		}
		try {
			notificationPort.notifyReconcileBacklog(
				BACKLOG_SUBJECT, targetCount, policy.reconcileBacklogThreshold());
			lastBacklogAlertAt = now;
		} catch (RuntimeException ex) {
			log.error("대사가 밀렸다는 알림을 보내지 못했다 targetCount={}", targetCount, ex);
		}
	}

	private boolean isWithinAlertInterval(LocalDateTime now) {
		return lastBacklogAlertAt != null && lastBacklogAlertAt.isAfter(policy.notifiedBefore(now));
	}

	/**
	 * 상태별로 나눠 고른다. 결과를 모르는 건에는 대사 유예가 없다 — 그 상태가 되었다는 것 자체가 답을
	 * 받아 앞 호출이 끝났다는 뜻이다. 결제사를 부르고 응답을 기다리는 건만 부른 지 유예가 지났는지를
	 * 함께 본다.
	 *
	 * <p>회차별 임계 시각은 정책이 간격표에서 계산해 준다. 조회에는 상태·집은 횟수·임계 시각만 남아야
	 * 인덱스를 그대로 타고, 간격을 정하는 것도 인프라가 아니라 정책의 일이다.
	 */
	private List<ReconcileTarget> findTargets(LocalDateTime now) {
		LocalDateTime requestedBefore = policy.requestedBefore(now);

		List<ReconcileTarget> targets = new ArrayList<>();
		for (ReconcileWindow window : policy.reconcileWindows(now)) {
			targets.addAll(refundRepository.findUnknownReconcileTargets(
				window.minReconcileCount(), window.maxReconcileCount(), window.reconciledBefore()));
			targets.addAll(refundRepository.findInProgressReconcileTargets(
				requestedBefore, window.minReconcileCount(), window.maxReconcileCount(),
				window.reconciledBefore()));
		}
		return targets;
	}

	private void reconcileOne(ReconcileTarget target) {
		Refund picked = pick(target).orElse(null);
		if (picked == null) {
			return;
		}
		try {
			settle(picked);
		} catch (Exception ex) {
			// 집은 뒤라 행을 손에 들고 있다. 대사가 무더기로 깨질 때 무엇이 어느 상태에서 깨지는지는
			// 이 값들로만 갈리며, 없으면 건마다 다시 조회해야 한다.
			log.error("환불 대사 처리 실패 refundId={} paymentId={} status={}",
				picked.getId(), picked.getPaymentId(), picked.getStatus(), ex);
		}
	}

	private void settle(Refund picked) {
		Payment payment = paymentRepository.findById(picked.getPaymentId())
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));

		PgHistoryResult history = paymentGatewayPort.readHistory(payment, PgHistoryScope.REFUND_ONLY, PgCallSource.BATCH);
		if (history.outcome() != PgOutcome.SUCCEEDED) {
			// 조회가 거절된 것은 그 취소가 없다는 뜻이 아니라 우리가 제대로 묻지 못했다는 뜻이다. 빈 목록과
			// 같게 다루면 그 자리에서 다시 보내게 되어, 이중환불을 막던 확인이 통째로 무력해진다.
			log.warn("이력 조회가 거절되어 환불을 확정하지도 다시 보내지도 않는다 refundId={} 사유={}",
				picked.getId(), history.message());
			return;
		}

		Optional<PgHistoryEntry> settled = history.settledRefundOf(picked);
		if (settled.isPresent()) {
			complete(picked, settled.get());
			return;
		}
		// 이력에 없거나 실패로만 있다. 둘 다 같은 키로 다시 부른다 — 앞엣것은 이미 나갔을 수 있어서이고,
		// 뒤엣것은 이력에 없는 실패 사유를 되돌아오는 이전 응답이 알려주기 때문이다.
		executeRefundUseCase.resend(payment, picked, PgCallSource.BATCH);
	}

	/**
	 * 집었다는 사실을 결제사를 부르기 전에 따로 커밋한다. 다른 주기가 같은 건을 이미 집었으면 두 가지로
	 * 갈린다 — 그 갱신이 이미 커밋됐으면 고를 때 본 회차와 달라져 빈 결과가 오고, 아직 커밋 전이면 낙관
	 * 락이 잡는다. 어느 쪽이든 부르지 않고 물러난다.
	 *
	 * @return 집은 환불. 물러났으면 비어 있다
	 */
	private Optional<Refund> pick(ReconcileTarget target) {
		try {
			Optional<Refund> picked =
				refundService.recordReconciled(target.id(), target.reconcileCount(), LocalDateTime.now());
			if (picked.isEmpty()) {
				log.info("다른 주기가 먼저 집어 이번 주기는 물러난다 refundId={}", target.id());
			}
			return picked;
		} catch (PaymentException ex) {
			if (ex.getErrorCode() == PaymentErrorCode.REFUND_CONCURRENTLY_MODIFIED) {
				log.info("다른 주기와 겹쳐 이번 주기는 물러난다 refundId={}", target.id());
				return Optional.empty();
			}
			throw ex;
		}
	}

	/** 결제사가 발급한 취소 거래 번호를 함께 남긴다. 판정에 쓰지 않고 정산 대조·추적에 쓴다 */
	private void complete(Refund refund, PgHistoryEntry settled) {
		try {
			refundService.complete(refund.getId(), settled.pgTransactionId());
		} catch (PaymentException ex) {
			if (ex.getErrorCode() == PaymentErrorCode.REFUND_SUCCEEDED_AMOUNT_INVARIANT_BROKEN) {
				// 저장된 두 금액이 이미 어긋났다는 뜻이라 다시 집어도 풀리지 않는다. 경합과 같은 수준으로
				// 남기면 그 신호가 정상 흐름 로그에 묻히고, 여기서 삼켜져 끝단에 닿지 않으므로 원인 위치는
				// 실어 주는 stack 에만 남는다.
				log.error("결제의 환불 금액 불변식이 깨져 이번 확정을 반영하지 못한다 refundId={}", refund.getId(), ex);
				handOver(refund, ex);
				return;
			}
			// 그 사이 다른 주체가 같은 결과로 옮겼거나, 결제가 동시에 바뀌어 이번 확정이 밀렸다. 어느
			// 쪽이든 돈이 어떻게 됐는지는 달라지지 않고 다음 주기가 다시 집는다.
			log.info("먼저 옮겨졌거나 결제가 함께 바뀌어 이번 확정을 반영하지 않는다 refundId={} 사유={}",
				refund.getId(), ex.getErrorCode());
		}
	}

	/**
	 * 자동으로 풀 수 없는 건을 사람이 이어받는 자리로 옮긴다. 그대로 두면 상태가 대사 대상 그대로라
	 * 주기마다 다시 집혀 결제사 이력 조회만 되풀이되고, 이미 나간 돈이 미결로 남는다.
	 *
	 * <p>이 전이는 환불만 저장하므로 방금 거부한 결제 행을 다시 건드리지 않는다. 밀리면 상태가 그대로라
	 * 다음 주기가 다시 집어 이 자리로 온다.
	 */
	private void handOver(Refund refund, PaymentException cause) {
		try {
			refundService.flagForReview(
				refund.getId(), RefundReviewCode.PAYMENT_AMOUNT_RECORD_BROKEN, cause.getErrorCode().getMessage());
		} catch (PaymentException ex) {
			log.info("검토 대기로 옮기지 못해 다음 주기로 넘긴다 refundId={} 사유={}",
				refund.getId(), ex.getErrorCode());
		}
	}
}
