package com.commerce.payment.application.usecase;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.commerce.payment.application.port.NotificationPort;
import com.commerce.payment.application.service.DelayPaymentReconcileService;
import com.commerce.payment.application.service.EscalateApprovePaymentService;
import com.commerce.payment.application.service.EscalateCancelPaymentService;
import com.commerce.payment.application.service.FailApprovePaymentService;
import com.commerce.payment.application.service.FailCancelPaymentService;
import com.commerce.payment.application.service.MarkUnknownCancelPaymentService;
import com.commerce.payment.application.service.SucceedCancelPaymentService;
import com.commerce.payment.application.port.result.CancelOutcome;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.naverpay.application.port.NaverPayGateway;
import com.commerce.payment.naverpay.application.port.result.NaverPayCancelResult;
import com.commerce.payment.naverpay.application.port.result.NaverPayHistoryResult;
import com.commerce.payment.postprocess.flow.PaymentPostProcessFlow;
import com.commerce.payment.postprocess.flow.PaymentPostProcessFlowPolicy;
import com.commerce.payment.postprocess.flow.PaymentVerificationStatus;
import com.commerce.payment.postprocess.target.PaymentPostProcessTarget;
import com.commerce.payment.postprocess.target.PaymentPostProcessTargetPolicy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReconcilePaymentUseCase {

	// 한 주기 처리 상한. 운영 config 승격 전제.
	private static final int RECONCILE_BATCH_SIZE = 100;

	// UNKNOWN 진입 지연(UNKNOWN_RECONCILE_DELAY = 1분). 1분 미만 UNKNOWN은 대사 대상 제외. 정책과 단일 출처 공유.
	private static final long STALE_CUTOFF_MINUTES = PaymentPostProcessTargetPolicy.UNKNOWN_RECONCILE_DELAY.toMinutes();

	// REQUESTED 진입 지연(REQUESTED_STALE_DELAY = 15분). 15분 미만 REQUESTED는 스캔 후보에서 제외해 starvation을 막는다. 정책과 단일 출처 공유.
	private static final long REQUESTED_STALE_CUTOFF_MINUTES = PaymentPostProcessTargetPolicy.REQUESTED_STALE_DELAY.toMinutes();

	// 자동 대사 스캔 상한(ESCALATION_DELAY = 6시간). 6시간 초과는 스캔에서 제외해 무한 재시도를 방지한다. 정책과 단일 출처 공유.
	private static final long ESCALATION_DELAY_HOURS = PaymentPostProcessTargetPolicy.ESCALATION_DELAY.toHours();

	private final PaymentRepository paymentRepository;
	private final DelayPaymentReconcileService delayPaymentReconcileService;
	private final FailApprovePaymentService failApprovePaymentService;
	private final EscalateApprovePaymentService escalateApprovePaymentService;
	private final EscalateCancelPaymentService escalateCancelPaymentService;
	private final SucceedCancelPaymentService succeedCancelPaymentService;
	private final FailCancelPaymentService failCancelPaymentService;
	private final MarkUnknownCancelPaymentService markUnknownCancelPaymentService;
	private final ConfirmApprovalUseCase confirmApprovalUseCase;
	private final NaverPayGateway naverPayGateway;
	private final PaymentPostProcessTargetPolicy targetPolicy;
	private final PaymentPostProcessFlowPolicy flowPolicy;
	private final NotificationPort notificationPort;

	/**
	 * stale APPROVE UNKNOWN/REQUESTED 결제와 stale CANCEL UNKNOWN/REQUESTED 결제를 PG 조회로 확정한다.
	 * PG 조회(외부 호출)는 트랜잭션 경계 밖에서 수행하고, 상태 확정은 건별 단건 트랜잭션으로 처리한다.
	 */
	public void reconcile() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime staleCutoff = now.minusMinutes(STALE_CUTOFF_MINUTES);
		LocalDateTime requestedStaleCutoff = now.minusMinutes(REQUESTED_STALE_CUTOFF_MINUTES);
		LocalDateTime escalationCutoff = now.minusHours(ESCALATION_DELAY_HOURS);

		List<Payment> candidates = paymentRepository.findStaleApprovePaymentsForReconciliation(
			staleCutoff, requestedStaleCutoff, escalationCutoff, now, PageRequest.of(0, RECONCILE_BATCH_SIZE));

		if (!candidates.isEmpty()) {
			log.info("APPROVE 대사 시작 candidates={} staleCutoff={} requestedStaleCutoff={} escalationCutoff={}",
				candidates.size(), staleCutoff, requestedStaleCutoff, escalationCutoff);

			int succeeded = 0, failed = 0, skipped = 0, errors = 0;
			for (Payment payment : candidates) {
				try {
					PaymentReconcileOutcome outcome = processOne(payment, now);
					switch (outcome) {
						case SUCCEEDED -> succeeded++;
						case FAILED -> failed++;
						case SKIPPED -> skipped++;
					}
				} catch (Exception ex) {
					errors++;
					log.error("APPROVE 대사 처리 실패 paymentId={} merchantPayKey={} pgPaymentId={} status={}",
						payment.getId(), payment.getMerchantPayKey(), payment.getPgPaymentId(), payment.getStatus(), ex);
				}
			}

			log.info("APPROVE 대사 완료 succeeded={} failed={} skipped={} errors={}",
				succeeded, failed, skipped, errors);
		}

		// CANCEL 대사: standalone CANCEL(REQUESTED/UNKNOWN) 스캔 → PG 재조회 → 재시도/확정 (ADR-L4)
		List<Payment> cancelCandidates = paymentRepository.findStaleCancelPaymentsForReconciliation(
			staleCutoff, requestedStaleCutoff, escalationCutoff, now, PageRequest.of(0, RECONCILE_BATCH_SIZE));

		if (!cancelCandidates.isEmpty()) {
			log.info("CANCEL 대사 시작 candidates={}", cancelCandidates.size());

			int succeeded = 0, failed = 0, skipped = 0, errors = 0;
			for (Payment payment : cancelCandidates) {
				try {
					PaymentReconcileOutcome outcome = processCancelOne(payment, now);
					switch (outcome) {
						case SUCCEEDED -> succeeded++;
						case FAILED -> failed++;
						case SKIPPED -> skipped++;
					}
				} catch (Exception ex) {
					errors++;
					log.error("CANCEL 대사 처리 실패 paymentId={} merchantPayKey={} pgPaymentId={} status={}",
						payment.getId(), payment.getMerchantPayKey(), payment.getPgPaymentId(), payment.getStatus(), ex);
				}
			}

			log.info("CANCEL 대사 완료 succeeded={} failed={} skipped={} errors={}",
				succeeded, failed, skipped, errors);
		}

		processEscalations(now, escalationCutoff);
	}

	private void processEscalations(LocalDateTime now, LocalDateTime escalationCutoff) {
		// APPROVE escalation
		List<Payment> candidates = paymentRepository.findEscalationCandidates(
			escalationCutoff, PageRequest.of(0, RECONCILE_BATCH_SIZE));

		if (!candidates.isEmpty()) {
			log.info("APPROVE escalation 처리 시작 candidates={} escalationCutoff={}", candidates.size(), escalationCutoff);

			// useCase(트랜잭션 없음): escalate transition은 별도 빈(public @Transactional)이라 충돌 시 그 트랜잭션만 rollback된다.
			for (Payment payment : candidates) {
				try {
					if (escalateSkippable(payment, now)) {
						// transition 성공 = 이 건이 통지 주체 → 커밋 이후 통지 (best-effort)
						notifyEscalation(payment);
					}
				} catch (Exception ex) {
					log.error("APPROVE escalation 처리 실패 paymentId={} orderId={} merchantPayKey={}",
						payment.getId(), payment.getOrderId(), payment.getMerchantPayKey(), ex);
				}
			}
		}

		// CANCEL escalation: 6시간 초과 UNKNOWN/REQUESTED + FAILED CANCEL (ADR-L4)
		List<Payment> cancelCandidates = paymentRepository.findCancelEscalationCandidates(
			escalationCutoff, PageRequest.of(0, RECONCILE_BATCH_SIZE));

		if (!cancelCandidates.isEmpty()) {
			log.info("CANCEL escalation 처리 시작 candidates={}", cancelCandidates.size());

			for (Payment payment : cancelCandidates) {
				try {
					if (escalateCancelSkippable(payment, now)) {
						notifyCancelEscalation(payment);
					}
				} catch (Exception ex) {
					log.error("CANCEL escalation 처리 실패 paymentId={} orderId={} merchantPayKey={}",
						payment.getId(), payment.getOrderId(), payment.getMerchantPayKey(), ex);
				}
			}
		}
	}

	/**
	 * escalate transition을 호출하되 PAYMENT_CONCURRENTLY_MODIFIED(다른 주체가 먼저 escalation)는 흡수해 통지 주체에서 빠진다(skip).
	 * 충돌은 처리 실패가 아니라 정상 skip이므로 log.info로 남기고, 그 외 도메인 예외는 rethrow해 호출부의 건별 catch(log.error)가 받게 한다.
	 * @return 이 건이 통지 주체이면 true, escalation 대상 아님(no-op)/충돌 skip이면 false.
	 */
	private boolean escalateSkippable(Payment payment, LocalDateTime now) {
		try {
			return escalateApprovePaymentService.escalate(
				payment.getMerchantPayKey(), payment.getProvider(), payment.getPgPaymentId(), now);
		} catch (PaymentException ex) {
			if (ex.getErrorCode() == PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED) {
				log.info("escalation skip - 낙관적 락 충돌, 이미 다른 주체가 처리 paymentId={} orderId={}",
					payment.getId(), payment.getOrderId());
				return false;
			}
			throw ex;
		}
	}

	private void notifyEscalation(Payment payment) {
		try {
			notificationPort.notifyManualReviewRequired(
				payment.getOrderId(), payment.getMerchantPayKey(), "escalation: 6시간 초과 미확정 APPROVE");
		} catch (Exception ex) {
			log.warn("escalation 통지 전송 실패 paymentId={} orderId={} merchantPayKey={}",
				payment.getId(), payment.getOrderId(), payment.getMerchantPayKey(), ex);
		}
	}

	/**
	 * CANCEL escalation transition을 호출하되 PAYMENT_CONCURRENTLY_MODIFIED(다른 주체가 먼저 escalation)는 흡수해 통지 주체에서 빠진다(skip).
	 * @return 이 건이 통지 주체이면 true, escalation 대상 아님(no-op)/충돌 skip이면 false.
	 */
	private boolean escalateCancelSkippable(Payment payment, LocalDateTime now) {
		try {
			return escalateCancelPaymentService.escalate(
				payment.getMerchantPayKey(), payment.getProvider(), payment.getPgPaymentId(), now);
		} catch (PaymentException ex) {
			if (ex.getErrorCode() == PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED) {
				log.info("CANCEL escalation skip - 낙관적 락 충돌, 이미 다른 주체가 처리 paymentId={} orderId={}",
					payment.getId(), payment.getOrderId());
				return false;
			}
			throw ex;
		}
	}

	private void notifyCancelEscalation(Payment payment) {
		String reason = payment.getStatus() == PaymentStatus.FAILED
			? "escalation: CANCEL 확정 실패(환불 미집행)"
			: "escalation: 6시간 초과 미확정 CANCEL";
		try {
			notificationPort.notifyManualReviewRequired(
				payment.getOrderId(), payment.getMerchantPayKey(), reason);
		} catch (Exception ex) {
			log.warn("CANCEL escalation 통지 전송 실패 paymentId={} orderId={} merchantPayKey={}",
				payment.getId(), payment.getOrderId(), payment.getMerchantPayKey(), ex);
		}
	}

	/**
	 * CANCEL 대사 단건 처리. resolvePostProcessTarget(null, cancelPayment, now) → CANCEL_RECONCILE → PG 조회 → 결과 반영.
	 */
	private PaymentReconcileOutcome processCancelOne(Payment cancelPayment, LocalDateTime now) {
		PaymentPostProcessTarget target = targetPolicy.resolvePostProcessTarget(null, cancelPayment, now);

		return switch (target) {
			case CANCEL_RECONCILE -> processCancelReconcile(cancelPayment, now);
			case MANUAL_REVIEW -> {
				log.warn("CANCEL 대사 escalation 임계 초과 - 상태 변경 없이 건너뜀 paymentId={} orderId={} merchantPayKey={}",
					cancelPayment.getId(), cancelPayment.getOrderId(), cancelPayment.getMerchantPayKey());
				yield PaymentReconcileOutcome.SKIPPED;
			}
			default -> {
				log.debug("CANCEL 대사 범위 밖 target={} paymentId={}", target, cancelPayment.getId());
				yield PaymentReconcileOutcome.SKIPPED;
			}
		};
	}

	private PaymentReconcileOutcome processCancelReconcile(Payment cancelPayment, LocalDateTime now) {
		// PG 조회: 트랜잭션 경계 밖에서 수행
		NaverPayHistoryResult historyResult = naverPayGateway.getApprovalHistory(cancelPayment.getPgPaymentId());
		PaymentVerificationStatus verificationStatus = toVerificationStatus(historyResult);
		PaymentPostProcessFlow flow = flowPolicy.resolveFlow(PaymentPostProcessTarget.CANCEL_RECONCILE, verificationStatus);

		switch (flow) {
			case ALREADY_CANCELED_PAYMENT_PROCESS -> {
				// PG에서 이미 취소됨 → CANCEL SUCCEEDED 확정
				succeedCancelSkippable(cancelPayment, now);
				log.info("CANCEL 대사 취소 확정 paymentId={} orderId={} merchantPayKey={}",
					cancelPayment.getId(), cancelPayment.getOrderId(), cancelPayment.getMerchantPayKey());
				return PaymentReconcileOutcome.SUCCEEDED;
			}
			case CANCEL_RETRY_PROCESS -> {
				// PG에서 아직 승인 유지 → 취소 재시도
				return executeCancelRetry(cancelPayment, now);
			}
			case KEEP_WAITING -> {
				log.debug("CANCEL 대사 대기 paymentId={} status={} verificationStatus={}",
					cancelPayment.getId(), cancelPayment.getStatus(), verificationStatus);
				delayReconcileSkippable(cancelPayment, PaymentType.CANCEL, now);
				return PaymentReconcileOutcome.SKIPPED;
			}
			default -> {
				log.warn("예상치 못한 CANCEL 대사 flow={} paymentId={}", flow, cancelPayment.getId());
				return PaymentReconcileOutcome.SKIPPED;
			}
		}
	}

	private PaymentReconcileOutcome executeCancelRetry(Payment cancelPayment, LocalDateTime now) {
		NaverPayCancelResult result = naverPayGateway.cancel(
			cancelPayment.getPgPaymentId(), cancelPayment.getAmount(), "환불 재시도");
		switch (result.getStatus()) {
			case SUCCESS, ALREADY_CANCELED -> {
				succeedCancelSkippable(cancelPayment, now);
				log.info("CANCEL 대사 취소 재시도 성공 paymentId={} orderId={} merchantPayKey={}",
					cancelPayment.getId(), cancelPayment.getOrderId(), cancelPayment.getMerchantPayKey());
				return PaymentReconcileOutcome.SUCCEEDED;
			}
			case PROCESSING -> {
				log.debug("CANCEL 대사 재시도 처리 중 paymentId={}", cancelPayment.getId());
				delayReconcileSkippable(cancelPayment, PaymentType.CANCEL, now);
				return PaymentReconcileOutcome.SKIPPED;
			}
			case FAILED -> {
				failCancelSkippable(cancelPayment, result.getFailCode(), result.getFailDetail(), now);
				log.warn("CANCEL 대사 취소 재시도 실패 paymentId={} orderId={} merchantPayKey={}",
					cancelPayment.getId(), cancelPayment.getOrderId(), cancelPayment.getMerchantPayKey());
				return PaymentReconcileOutcome.FAILED;
			}
			case UNKNOWN -> {
				markUnknownCancelSkippable(cancelPayment, result.getFailDetail(), now);
				log.warn("CANCEL 대사 취소 재시도 결과 불명 paymentId={} orderId={} merchantPayKey={}",
					cancelPayment.getId(), cancelPayment.getOrderId(), cancelPayment.getMerchantPayKey());
				return PaymentReconcileOutcome.SKIPPED;
			}
			default -> {
				log.warn("예상치 못한 CANCEL PG 결과 status={} paymentId={}", result.getStatus(), cancelPayment.getId());
				return PaymentReconcileOutcome.SKIPPED;
			}
		}
	}

	private void succeedCancelSkippable(Payment cancelPayment, LocalDateTime now) {
		try {
			succeedCancelPaymentService.succeed(
				cancelPayment.getMerchantPayKey(), cancelPayment.getProvider(),
				cancelPayment.getPgPaymentId(), now);
		} catch (PaymentException ex) {
			if (ex.getErrorCode() == PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED
				|| ex.getErrorCode() == PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED
				|| ex.getErrorCode() == PaymentErrorCode.PAYMENT_RECORD_NOT_FOUND) {
				log.info("CANCEL 대사 성공 마킹 skip - {} paymentId={}", ex.getErrorCode(), cancelPayment.getId());
				return;
			}
			throw ex;
		}
	}

	private void failCancelSkippable(Payment cancelPayment, PaymentFailCode failCode, String failDetail, LocalDateTime now) {
		try {
			failCancelPaymentService.fail(
				cancelPayment.getMerchantPayKey(), cancelPayment.getProvider(),
				cancelPayment.getPgPaymentId(), failCode, failDetail, now);
		} catch (PaymentException ex) {
			if (ex.getErrorCode() == PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED
				|| ex.getErrorCode() == PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED
				|| ex.getErrorCode() == PaymentErrorCode.PAYMENT_RECORD_NOT_FOUND) {
				log.info("CANCEL 대사 실패 마킹 skip - {} paymentId={}", ex.getErrorCode(), cancelPayment.getId());
				return;
			}
			throw ex;
		}
	}

	private void markUnknownCancelSkippable(Payment cancelPayment, String failDetail, LocalDateTime now) {
		try {
			markUnknownCancelPaymentService.markUnknown(
				cancelPayment.getMerchantPayKey(), cancelPayment.getProvider(),
				cancelPayment.getPgPaymentId(), failDetail, now);
		} catch (PaymentException ex) {
			if (ex.getErrorCode() == PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED
				|| ex.getErrorCode() == PaymentErrorCode.PAYMENT_STATUS_TRANSITION_NOT_ALLOWED
				|| ex.getErrorCode() == PaymentErrorCode.PAYMENT_RECORD_NOT_FOUND) {
				log.info("CANCEL 대사 UNKNOWN 마킹 skip - {} paymentId={}", ex.getErrorCode(), cancelPayment.getId());
				return;
			}
			throw ex;
		}
	}

	/**
	 * backoff 기록을 best-effort로 수행한다. 낙관적 락 충돌·행 없음은 흡수해 대사 루프가 중단되지 않도록 한다.
	 * backoff는 cadence 힌트이므로 충돌 시 건너뛰어도 다음 주기에 자연히 재시도된다(ADR-L3).
	 */
	private void delayReconcileSkippable(Payment payment, PaymentType type, LocalDateTime now) {
		try {
			delayPaymentReconcileService.delay(
				payment.getMerchantPayKey(), payment.getProvider(), payment.getPgPaymentId(), type, now);
		} catch (PaymentException ex) {
			if (ex.getErrorCode() == PaymentErrorCode.PAYMENT_CONCURRENTLY_MODIFIED
				|| ex.getErrorCode() == PaymentErrorCode.PAYMENT_RECORD_NOT_FOUND) {
				log.info("대사 backoff 기록 skip - {} paymentId={}", ex.getErrorCode(), payment.getId());
				return;
			}
			throw ex;
		}
	}

	private PaymentReconcileOutcome processOne(Payment payment, LocalDateTime now) {
		PaymentPostProcessTarget target = targetPolicy.resolvePostProcessTarget(payment, null, now);

		return switch (target) {
			case APPROVE_RECONCILE -> processApproveReconcile(payment, now);
			case MANUAL_REVIEW -> {
				// escalation(6시간 초과)은 step 2 스캔 윈도우 상한으로 처리. 상태 변경 없이 로그만 남긴다 (후속 #238).
				log.warn("대사 escalation 임계 초과 - 상태 변경 없이 건너뜀 paymentId={} orderId={} merchantPayKey={}",
					payment.getId(), payment.getOrderId(), payment.getMerchantPayKey());
				yield PaymentReconcileOutcome.SKIPPED;
			}
			case NONE -> PaymentReconcileOutcome.SKIPPED;
			case CANCEL_RECONCILE, APPROVED_CANCEL_COMPENSATION -> {
				log.debug("대사 APPROVE 범위 밖 target={} paymentId={}", target, payment.getId());
				yield PaymentReconcileOutcome.SKIPPED;
			}
		};
	}

	private PaymentReconcileOutcome processApproveReconcile(Payment payment, LocalDateTime now) {
		// PG 조회: 트랜잭션 경계 밖에서 수행
		NaverPayHistoryResult historyResult = naverPayGateway.getApprovalHistory(payment.getPgPaymentId());
		PaymentVerificationStatus verificationStatus = toVerificationStatus(historyResult);
		PaymentPostProcessFlow flow = flowPolicy.resolveFlow(PaymentPostProcessTarget.APPROVE_RECONCILE, verificationStatus);

		switch (flow) {
			case APPROVED_PAYMENT_PROCESS -> {
				return executeApprove(payment, now, historyResult);
			}
			case ALREADY_CANCELED_PAYMENT_PROCESS -> {
				executeAlreadyCanceled(payment, now);
				return PaymentReconcileOutcome.FAILED;
			}
			case KEEP_WAITING -> {
				log.debug("대사 대기 paymentId={} status={} verificationStatus={}",
					payment.getId(), payment.getStatus(), verificationStatus);
				delayReconcileSkippable(payment, PaymentType.APPROVE, now);
				return PaymentReconcileOutcome.SKIPPED;
			}
			default -> {
				log.warn("예상치 못한 대사 flow={} paymentId={}", flow, payment.getId());
				return PaymentReconcileOutcome.SKIPPED;
			}
		}
	}

	private PaymentReconcileOutcome executeApprove(Payment payment, LocalDateTime now, NaverPayHistoryResult historyResult) {
		// merchantPayKey/금액 검증은 facade 안에서 수행한다 — 검증 실패도 facade 보상 분기로 흐르도록 보장한다(M1, 비대칭 제거)
		ConfirmApprovalUseCase.Outcome outcome = confirmApprovalUseCase.confirm(
			payment, now, this::pgCancelForReconciliation,
			historyResult.getMerchantPayKey(), historyResult.getTotalPayAmount());

		return switch (outcome.decision()) {
			case SUCCEEDED -> {
				log.info("대사 승인 확정 paymentId={} orderId={} merchantPayKey={}",
					payment.getId(), payment.getOrderId(), payment.getMerchantPayKey());
				yield PaymentReconcileOutcome.SUCCEEDED;
			}
			case REJECTED -> PaymentReconcileOutcome.FAILED;
			case PROPAGATE -> throw outcome.cause();
		};
	}

	private CancelOutcome pgCancelForReconciliation(Payment cancelPayment, String cancelReason) {
		NaverPayCancelResult result = naverPayGateway.cancel(
			cancelPayment.getPgPaymentId(), cancelPayment.getAmount(), cancelReason
		);
		return switch (result.getStatus()) {
			case SUCCESS, ALREADY_CANCELED -> CancelOutcome.success();
			case PROCESSING -> CancelOutcome.processing();
			case FAILED -> CancelOutcome.failed(result.getFailCode(), result.getFailDetail());
			case UNKNOWN -> CancelOutcome.unknown(result.getFailDetail());
		};
	}

	private void executeAlreadyCanceled(Payment payment, LocalDateTime now) {
		failApprovePaymentService.fail(
			payment.getMerchantPayKey(), payment.getProvider(), payment.getPgPaymentId(),
			PaymentFailCode.ALREADY_CANCELED, "PG 이미 취소 확인", now
		);
		log.info("대사 취소 확정 paymentId={} orderId={} merchantPayKey={}",
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
		SUCCEEDED, FAILED, SKIPPED
	}
}
