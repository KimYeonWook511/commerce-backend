package com.commerce.payment.application.usecase;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import com.commerce.payment.application.port.PaymentGatewayPort;
import com.commerce.payment.application.port.dto.PgCallSource;
import com.commerce.payment.application.port.dto.PgHistoryEntry;
import com.commerce.payment.application.port.dto.PgHistoryResult;
import com.commerce.payment.application.port.dto.PgHistoryScope;
import com.commerce.payment.application.port.dto.PgOutcome;
import com.commerce.payment.application.service.RefundService;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.domain.policy.ReconcileWindow;
import com.commerce.payment.domain.policy.RefundPostProcessPolicy;
import com.commerce.payment.domain.repository.PaymentRepository;
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
 * <p>환불만 로드해 바꾼다. 상태 전이는 한도를 바꾸지 않으므로 결제를 건드리지 않는다 — 결제 버전이
 * 오르면 대사가 도는 동안 회원의 환불 요청이 낙관 락 충돌로 밀린다. 결제를 읽는 것은 결제사를 부르는 데
 * 필요한 값이 그 행에 있어서다.
 *
 * <p>트랜잭션을 열지 않는다. 건마다 단위작업이 따로 커밋되어 한 건이 실패해도 나머지가 돌고, 낙관 락
 * 충돌도 건별로 걸린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconcileRefundUseCase {

	/** 한 회차에서 한 주기에 집는 상한. 다음 주기가 나머지를 이어 집는다 */
	private static final int BATCH_SIZE = 100;

	private final RefundRepository refundRepository;
	private final PaymentRepository paymentRepository;
	private final PaymentGatewayPort paymentGatewayPort;
	private final RefundService refundService;
	private final ExecuteRefundUseCase executeRefundUseCase;
	private final RefundPostProcessPolicy policy;

	public void reconcile() {
		List<Refund> targets = findTargets(LocalDateTime.now());
		if (targets.isEmpty()) {
			return;
		}

		log.info("환불 대사 시작 targets={}", targets.size());
		for (Refund target : targets) {
			try {
				reconcileOne(target);
			} catch (Exception ex) {
				log.error("환불 대사 처리 실패 refundId={} paymentId={} status={}",
					target.getId(), target.getPaymentId(), target.getStatus(), ex);
			}
		}
	}

	/**
	 * 상태별로 나눠 고른다. 결과를 모르는 건에는 대사 유예가 없다 — 그 상태가 되었다는 것 자체가 답을
	 * 받아 앞 호출이 끝났다는 뜻이다. 결제사를 부르고 응답을 기다리는 건만 부른 지 유예가 지났는지를
	 * 함께 본다.
	 *
	 * <p>회차별 임계 시각은 정책이 간격표에서 계산해 준다. 조회에는 상태·집은 횟수·임계 시각만 남아야
	 * 인덱스를 그대로 타고, 간격을 정하는 것도 인프라가 아니라 정책의 일이다.
	 */
	private List<Refund> findTargets(LocalDateTime now) {
		LocalDateTime requestedBefore = policy.requestedBefore(now);
		Pageable page = PageRequest.of(0, BATCH_SIZE);

		List<Refund> targets = new ArrayList<>();
		for (ReconcileWindow window : policy.reconcileWindows(now)) {
			targets.addAll(refundRepository.findUnknownReconcileTargets(
				window.minReconcileCount(), window.maxReconcileCount(), window.reconciledBefore(), page));
			targets.addAll(refundRepository.findInProgressReconcileTargets(
				requestedBefore, window.minReconcileCount(), window.maxReconcileCount(),
				window.reconciledBefore(), page));
		}
		return targets;
	}

	private void reconcileOne(Refund target) {
		Refund picked = pick(target);
		if (picked == null) {
			return;
		}

		Payment payment = paymentRepository.findById(picked.getPaymentId())
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));

		PgHistoryResult history = paymentGatewayPort.readHistory(payment, PgHistoryScope.REFUND_ONLY);
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
	 * 집었다는 사실을 결제사를 부르기 전에 따로 커밋한다. 이 저장에 지면 다른 주기가 같은 건을 이미
	 * 집었다는 뜻이라 부르지 않고 물러난다.
	 *
	 * @return 집은 환불. 물러났으면 {@code null}
	 */
	private Refund pick(Refund target) {
		try {
			return refundService.recordReconciled(target.getId(), LocalDateTime.now());
		} catch (PaymentException ex) {
			if (ex.getErrorCode() == PaymentErrorCode.REFUND_CONCURRENTLY_MODIFIED) {
				log.info("다른 주기가 먼저 집어 이번 주기는 물러난다 refundId={}", target.getId());
				return null;
			}
			throw ex;
		}
	}

	/** 결제사가 발급한 취소 거래 번호를 함께 남긴다. 판정에 쓰지 않고 정산 대조·추적에 쓴다 */
	private void complete(Refund refund, PgHistoryEntry settled) {
		try {
			refundService.complete(refund.getId(), settled.pgTransactionId());
		} catch (PaymentException ex) {
			// 그 사이 다른 주체가 같은 결과로 옮겼다. 돈이 어떻게 됐는지는 달라지지 않는다.
			log.info("다른 주체가 먼저 환불을 옮겨 이번 확정을 반영하지 않는다 refundId={} 사유={}",
				refund.getId(), ex.getErrorCode());
		}
	}
}
