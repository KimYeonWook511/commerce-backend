package com.commerce.payment.application.usecase;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import com.commerce.payment.application.dto.ApprovalOutcome;
import com.commerce.payment.application.port.PaymentGatewayPort;
import com.commerce.payment.application.port.dto.PgCallSource;
import com.commerce.payment.application.port.dto.PgApproveResult;
import com.commerce.payment.application.port.dto.PgHistoryResult;
import com.commerce.payment.application.port.dto.PgHistoryScope;
import com.commerce.payment.application.port.dto.PgOutcome;
import com.commerce.payment.application.service.PaymentService;
import com.commerce.payment.application.service.PgCallLogService;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentCloseCode;
import com.commerce.payment.domain.PgCallLog;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.domain.policy.PaymentPostProcessPolicy;
import com.commerce.payment.domain.policy.ReconcileWindow;
import com.commerce.payment.domain.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결과를 모르는 결제를 이력으로 확정한다. 주기 실행으로만 돌고 밖에서 부를 수 있는 경로가 없다.
 *
 * <p>한 주기에 두 번 묻는다 — 이력을 먼저 읽고, 거기 없으면 그 자리에서 승인을 다시 부른다. 이력이
 * 비었다는 것만으로 실패를 확정하지 않는 것이 이 구조의 핵심이다. 빈 목록은 돈이 안 나간 것과 결제사가
 * 아직 반영하지 않은 것을 구분하지 못하고, 잘못 확정하면 슬롯이 풀려 회원이 다시 결제해 돈이 두 번
 * 나간다.
 *
 * <p>회수를 멈추지 않는다. 멈추면 결과를 모르는 결제가 활성 슬롯을 쥔 채 남아 그 주문을 영영 결제할 수
 * 없고, 그 건은 자동으로도 수동으로도 풀리지 않는다. 상한이 있는 것은 다시 집는 간격뿐이다.
 *
 * <p>트랜잭션을 열지 않는다. 건마다 단위작업이 따로 커밋되어 한 건이 실패해도 나머지가 돌고, 낙관 락
 * 충돌도 건별로 걸린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconcilePaymentUseCase {

	/** 한 회차에서 한 주기에 집는 상한. 다음 주기가 나머지를 이어 집는다 */
	private static final int BATCH_SIZE = 100;

	private final PaymentRepository paymentRepository;
	private final PaymentGatewayPort paymentGatewayPort;
	private final PaymentService paymentService;
	private final PgCallLogService pgCallLogService;
	private final ConfirmApprovalUseCase confirmApprovalUseCase;
	private final PaymentPostProcessPolicy policy;

	public void reconcile() {
		List<Payment> targets = findTargets(LocalDateTime.now());
		if (targets.isEmpty()) {
			return;
		}

		log.info("결제 대사 시작 targets={}", targets.size());
		for (Payment target : targets) {
			try {
				reconcileOne(target);
			} catch (Exception ex) {
				log.error("결제 대사 처리 실패 paymentId={} orderId={} status={}",
					target.getId(), target.getOrderId(), target.getStatus(), ex);
			}
		}
	}

	/**
	 * 상태별로 나눠 고른다. 결과를 모르는 건에는 대사 유예가 없다 — 그 상태가 되었다는 것 자체가 요청
	 * 흐름이 끝났다는 뜻이라 빨리 읽어 확정해야 한다. 승인을 부르고 응답을 기다리는 건만 부른 지 유예가
	 * 지났는지를 함께 본다.
	 *
	 * <p>회차별 임계 시각은 정책이 간격표에서 계산해 준다. 조회에는 상태·집은 횟수·임계 시각만 남아야
	 * 인덱스를 그대로 타고, 간격을 정하는 것도 인프라가 아니라 정책의 일이다.
	 */
	private List<Payment> findTargets(LocalDateTime now) {
		LocalDateTime requestedBefore = policy.requestedBefore(now);
		Pageable page = PageRequest.of(0, BATCH_SIZE);

		List<Payment> targets = new ArrayList<>();
		for (ReconcileWindow window : policy.reconcileWindows(now)) {
			targets.addAll(paymentRepository.findUnknownReconcileTargets(
				window.minReconcileCount(), window.maxReconcileCount(), window.reconciledBefore(), page));
			targets.addAll(paymentRepository.findInProgressReconcileTargets(
				requestedBefore, window.minReconcileCount(), window.maxReconcileCount(),
				window.reconciledBefore(), page));
		}
		return targets;
	}

	private void reconcileOne(Payment target) {
		Payment picked = pick(target);
		if (picked == null) {
			return;
		}

		PgHistoryResult history = paymentGatewayPort.readHistory(picked, PgHistoryScope.ALL, PgCallSource.BATCH);
		if (history.outcome() != PgOutcome.SUCCEEDED) {
			// 조회가 거절된 것은 그 거래가 없다는 뜻이 아니라 우리가 제대로 묻지 못했다는 뜻이다.
			// 빈 목록과 같은 처리로 흘려보내면 인증 설정이 틀린 순간 멀쩡한 결제가 전부 실패가 된다.
			log.warn("이력 조회가 거절되어 확정하지 않는다 paymentId={} 사유={}", picked.getId(), history.message());
			return;
		}

		if (confirmApprovalUseCase.confirmFromHistory(picked, history).decision() == ApprovalOutcome.Decision.UNRESOLVED) {
			requestApprovalAgain(picked);
		}
	}

	/**
	 * 집었다는 사실을 결제사를 부르기 전에 따로 커밋한다. 이 저장에 지면 다른 주기가 같은 건을 이미
	 * 집었다는 뜻이라 부르지 않고 물러난다.
	 *
	 * @return 집은 결제. 물러났으면 {@code null}
	 */
	private Payment pick(Payment target) {
		try {
			return paymentService.recordReconciled(target.getId(), LocalDateTime.now());
		} catch (PaymentException ex) {
			if (ex.getErrorCode() == PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED) {
				log.info("다른 주기가 먼저 집어 이번 주기는 물러난다 paymentId={}", target.getId());
				return null;
			}
			throw ex;
		}
	}

	/**
	 * 이력이 이 결제를 설명하지 못했다. 미루지 않고 그 자리에서 승인을 다시 부른다 — 미루는 사이 승인
	 * 가능 시간이 흘러가고, 결제사에 닿지 않았던 요청은 다시 부르기만 하면 성립할 것을 놓친 채 시간
	 * 초과로 실패한다.
	 *
	 * <p>승인 가능 시간 안에서 부르므로 이 호출로 돈이 움직일 수 있다. 회원이 결제창에서 인증까지 마친
	 * 건이라 의도된 움직임이고, 처리 중인 건에 겹쳐 승인되는 것은 결제사가 "아직 처리 중"으로 답해 막는다.
	 */
	private void requestApprovalAgain(Payment payment) {
		Payment calling = paymentService.recordRequested(payment.getId(), LocalDateTime.now());
		PgCallLog callLog = pgCallLogService.startApproveCall(
			calling.getId(), calling.pgIdempotencyKey(), LocalDateTime.now());

		PgApproveResult result = paymentGatewayPort.approve(calling);
		try {
			apply(calling, result);
		} finally {
			recordCallResult(callLog, result);
		}
	}

	private void apply(Payment payment, PgApproveResult result) {
		switch (result.outcome()) {
			case SUCCEEDED -> confirmApprovalUseCase.confirmApproved(payment, result);
			// 답은 받았는데 그 답이 결과를 정하지 못했다. 이미 성립한 승인이면 그 내용이 이력에 있고,
			// 승인 금액도 거기서 얻는다.
			case UNKNOWN -> {
				if (result.answered()) {
					confirmFromHistory(payment);
				} else {
					log.info("승인 재요청의 답을 받지 못해 다음 주기로 미룬다 paymentId={}", payment.getId());
				}
			}
			// 시간이 지나거나 설정을 고치면 풀리는 실패다. 상태를 그대로 두고 시도 번호만 올려 다음
			// 주기가 새 키로 부르게 한다.
			case RETRYABLE_FAILURE -> paymentService.recordRetryableFailure(payment.getId());
			case TERMINAL_FAILURE -> closeIfApprovalWindowClosed(payment, result);
		}
	}

	private void confirmFromHistory(Payment payment) {
		PgHistoryResult history = paymentGatewayPort.readHistory(payment, PgHistoryScope.ALL, PgCallSource.BATCH);
		if (history.outcome() != PgOutcome.SUCCEEDED) {
			log.warn("이력 조회가 거절되어 확정하지 않는다 paymentId={} 사유={}", payment.getId(), history.message());
			return;
		}
		confirmApprovalUseCase.confirmFromHistory(payment, history);
	}

	/**
	 * 승인 가능 시간이 지났다는 답만 실패를 확정한다. 그 답은 지금 승인이 없고 앞으로도 생기지 않는다를
	 * 함께 뜻하며 이력 반영 시점과 무관하다.
	 *
	 * <p>"이번 요청이 실패했다"는 답은 확정하지 않는다. 이번 호출에 대한 답이지 앞선 호출에 대한 답이
	 * 아니고, 시간이 남았으면 다음 주기에 다시 부른다.
	 */
	private void closeIfApprovalWindowClosed(Payment payment, PgApproveResult result) {
		if (!result.approvalWindowClosed()) {
			log.info("이번 승인 재요청이 실패했을 뿐이라 확정하지 않는다 paymentId={} 사유={}",
				payment.getId(), result.message());
			return;
		}
		paymentService.fail(payment.getId(), PaymentCloseCode.UNCONFIRMED_CLOSED, result.message());
	}

	/** 기록이 판정을 흔들지 않는다. 판정의 정본은 이미 커밋됐고 이 행은 조사에서만 읽는다 */
	private void recordCallResult(PgCallLog callLog, PgApproveResult result) {
		try {
			pgCallLogService.recordResult(callLog, LocalDateTime.now(), result.callRecord());
		} catch (RuntimeException ex) {
			log.error("승인 재요청 결과를 기록하지 못했다 pgCallLogId={}", callLog.getId(), ex);
		}
	}
}
