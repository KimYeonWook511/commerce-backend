package com.commerce.payment.application;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.naverpay.application.port.NaverPayGateway;
import com.commerce.payment.naverpay.application.port.result.NaverPayHistoryResult;
import com.commerce.payment.postprocess.flow.PaymentPostProcessFlow;
import com.commerce.payment.postprocess.flow.PaymentPostProcessFlowPolicy;
import com.commerce.payment.postprocess.flow.PaymentVerificationStatus;
import com.commerce.payment.postprocess.target.PaymentPostProcessTarget;
import com.commerce.payment.postprocess.target.PaymentPostProcessTargetPolicy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReconciliationService {

	// 한 주기 처리 상한. 운영 config 승격 전제.
	private static final int RECONCILE_BATCH_SIZE = 100;

	// 정책 최단 진입 지연(UNKNOWN_RECONCILE_DELAY = 1분)과 동일. 후보를 넓게 긁고 정밀 분기는 정책이 담당.
	private static final int STALE_CUTOFF_MINUTES = 1;

	private final PaymentRepository paymentRepository;
	private final PaymentApprovalService paymentApprovalService;
	private final PaymentApprovalRecordService paymentApprovalRecordService;
	private final NaverPayGateway naverPayGateway;
	private final PaymentPostProcessTargetPolicy targetPolicy;
	private final PaymentPostProcessFlowPolicy flowPolicy;

	/**
	 * stale APPROVE UNKNOWN/REQUESTED 결제를 PG 조회로 확정한다 (ADR-L1).
	 * PG 조회(외부 호출)는 트랜잭션 경계 밖에서 수행하고, 상태 확정은 건별 단건 트랜잭션으로 처리한다.
	 */
	public void reconcile() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime cutoff = now.minusMinutes(STALE_CUTOFF_MINUTES);

		List<Payment> candidates = paymentRepository.findStaleApprovePaymentsForReconciliation(
			cutoff, PageRequest.of(0, RECONCILE_BATCH_SIZE));

		if (candidates.isEmpty()) {
			return;
		}

		log.info("대사 시작 candidates={} cutoff={}", candidates.size(), cutoff);

		int succeeded = 0, failed = 0, manualReview = 0, skipped = 0, errors = 0;
		for (Payment payment : candidates) {
			try {
				PaymentReconcileOutcome outcome = processOne(payment, now);
				switch (outcome) {
					case SUCCEEDED -> succeeded++;
					case FAILED -> failed++;
					case MANUAL_REVIEW -> manualReview++;
					case SKIPPED -> skipped++;
				}
			} catch (Exception ex) {
				errors++;
				log.error("대사 처리 실패 paymentId={} merchantPayKey={} pgPaymentId={} status={}",
					payment.getId(), payment.getMerchantPayKey(), payment.getPgPaymentId(), payment.getStatus(), ex);
			}
		}

		log.info("대사 완료 succeeded={} failed={} manualReview={} skipped={} errors={}",
			succeeded, failed, manualReview, skipped, errors);
	}

	private PaymentReconcileOutcome processOne(Payment payment, LocalDateTime now) {
		PaymentPostProcessTarget target = targetPolicy.resolvePostProcessTarget(payment, null, now);

		return switch (target) {
			case APPROVE_RECONCILE -> processApproveReconcile(payment, now);
			case MANUAL_REVIEW -> {
				escalateToManualReview(payment, now);
				yield PaymentReconcileOutcome.MANUAL_REVIEW;
			}
			case NONE -> PaymentReconcileOutcome.SKIPPED;
			case CANCEL_RECONCILE, APPROVED_CANCEL_COMPENSATION -> {
				log.debug("대사 APPROVE 범위 밖 target={} paymentId={}", target, payment.getId());
				yield PaymentReconcileOutcome.SKIPPED;
			}
		};
	}

	private PaymentReconcileOutcome processApproveReconcile(Payment payment, LocalDateTime now) {
		// PG 조회: 트랜잭션 경계 밖에서 수행 (ADR-L1)
		NaverPayHistoryResult historyResult = naverPayGateway.getApprovalHistory(payment.getPgPaymentId());
		PaymentVerificationStatus verificationStatus = toVerificationStatus(historyResult);
		PaymentPostProcessFlow flow = flowPolicy.resolveFlow(PaymentPostProcessTarget.APPROVE_RECONCILE, verificationStatus);

		switch (flow) {
			case APPROVED_PAYMENT_PROCESS -> {
				return executeApprove(payment, now);
			}
			case ALREADY_CANCELED_PAYMENT_PROCESS -> {
				executeAlreadyCanceled(payment, now);
				return PaymentReconcileOutcome.FAILED;
			}
			case KEEP_WAITING -> {
				log.debug("대사 대기 paymentId={} status={} verificationStatus={}",
					payment.getId(), payment.getStatus(), verificationStatus);
				return PaymentReconcileOutcome.SKIPPED;
			}
			default -> {
				log.warn("예상치 못한 대사 flow={} paymentId={}", flow, payment.getId());
				return PaymentReconcileOutcome.SKIPPED;
			}
		}
	}

	private PaymentReconcileOutcome executeApprove(Payment payment, LocalDateTime now) {
		try {
			paymentApprovalService.succeedApproval(payment, now);
			log.info("대사 승인 확정 paymentId={} orderId={} merchantPayKey={}",
				payment.getId(), payment.getOrderId(), payment.getMerchantPayKey());
			return PaymentReconcileOutcome.SUCCEEDED;
		} catch (OrderException ex) {
			if (ex.getErrorCode() == OrderErrorCode.ORDER_PAID_NOT_ALLOWED) {
				// 주문이 이미 CANCELED → 보상 취소는 phase 2 step 2(C)에서 추가 예정
				log.warn("대사 승인 확정 - 주문 이미 취소됨, 건너뜀 paymentId={} orderId={}",
					payment.getId(), payment.getOrderId());
				return PaymentReconcileOutcome.SKIPPED;
			}
			throw ex;
		}
	}

	private void executeAlreadyCanceled(Payment payment, LocalDateTime now) {
		paymentApprovalRecordService.fail(
			payment.getMerchantPayKey(), payment.getProvider(), payment.getPgPaymentId(),
			PaymentFailCode.ALREADY_CANCELED, "PG 이미 취소 확인", now
		);
		log.info("대사 취소 확정 paymentId={} orderId={} merchantPayKey={}",
			payment.getId(), payment.getOrderId(), payment.getMerchantPayKey());
	}

	private void escalateToManualReview(Payment payment, LocalDateTime now) {
		paymentApprovalRecordService.markManualReview(
			payment.getMerchantPayKey(), payment.getProvider(), payment.getPgPaymentId(),
			"대사 자동 처리 상한 초과", now
		);
		log.error("대사 수동 검토 승급 paymentId={} orderId={} merchantPayKey={}",
			payment.getId(), payment.getOrderId(), payment.getMerchantPayKey());
	}

	private PaymentVerificationStatus toVerificationStatus(NaverPayHistoryResult result) {
		return switch (result.getStatus()) {
			case APPROVED -> PaymentVerificationStatus.PG_APPROVED;
			case CANCELED -> PaymentVerificationStatus.PG_CANCELED;
			// 네트워크/서버 오류로 이력조회 결과 불명 → 다음 주기 재시도
			case UNKNOWN -> PaymentVerificationStatus.PENDING;
			// 이력 없음(빈 목록) 또는 PG 요청 거절 → 이력 조회 불가로 처리
			case FAILED -> PaymentVerificationStatus.HISTORY_NOT_FOUND;
		};
	}

	private enum PaymentReconcileOutcome {
		SUCCEEDED, FAILED, MANUAL_REVIEW, SKIPPED
	}
}
