package com.commerce.payment.application;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.payment.application.port.result.CancelOutcome;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
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
@Service
@RequiredArgsConstructor
public class PaymentReconciliationService {

	// 한 주기 처리 상한. 운영 config 승격 전제.
	private static final int RECONCILE_BATCH_SIZE = 100;

	// 정책 최단 진입 지연(UNKNOWN_RECONCILE_DELAY = 1분). 1분 미만은 대사 대상 제외. 정책과 단일 출처 공유.
	private static final long STALE_CUTOFF_MINUTES = PaymentPostProcessTargetPolicy.UNKNOWN_RECONCILE_DELAY.toMinutes();

	// 자동 대사 스캔 상한(ESCALATION_DELAY = 6시간). 6시간 초과는 스캔에서 제외해 무한 재시도를 방지한다 (ADR-L8). 정책과 단일 출처 공유.
	private static final long ESCALATION_DELAY_HOURS = PaymentPostProcessTargetPolicy.ESCALATION_DELAY.toHours();

	private final PaymentRepository paymentRepository;
	private final OrderRepository orderRepository;
	private final PaymentApprovalService paymentApprovalService;
	private final PaymentApprovalRecordService paymentApprovalRecordService;
	private final PaymentApprovalCompensationService paymentApprovalCompensationService;
	private final NaverPayGateway naverPayGateway;
	private final PaymentPostProcessTargetPolicy targetPolicy;
	private final PaymentPostProcessFlowPolicy flowPolicy;

	/**
	 * stale APPROVE UNKNOWN/REQUESTED 결제를 PG 조회로 확정한다 (ADR-L1).
	 * PG 조회(외부 호출)는 트랜잭션 경계 밖에서 수행하고, 상태 확정은 건별 단건 트랜잭션으로 처리한다.
	 */
	public void reconcile() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime staleCutoff = now.minusMinutes(STALE_CUTOFF_MINUTES);
		LocalDateTime escalationCutoff = now.minusHours(ESCALATION_DELAY_HOURS);

		List<Payment> candidates = paymentRepository.findStaleApprovePaymentsForReconciliation(
			staleCutoff, escalationCutoff, PageRequest.of(0, RECONCILE_BATCH_SIZE));

		if (candidates.isEmpty()) {
			return;
		}

		log.info("대사 시작 candidates={} staleCutoff={} escalationCutoff={}", candidates.size(), staleCutoff, escalationCutoff);

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
				log.error("대사 처리 실패 paymentId={} merchantPayKey={} pgPaymentId={} status={}",
					payment.getId(), payment.getMerchantPayKey(), payment.getPgPaymentId(), payment.getStatus(), ex);
			}
		}

		log.info("대사 완료 succeeded={} failed={} skipped={} errors={}",
			succeeded, failed, skipped, errors);
	}

	private PaymentReconcileOutcome processOne(Payment payment, LocalDateTime now) {
		PaymentPostProcessTarget target = targetPolicy.resolvePostProcessTarget(payment, null, now);

		return switch (target) {
			case APPROVE_RECONCILE -> processApproveReconcile(payment, now);
			case MANUAL_REVIEW -> {
				// escalation(6시간 초과)은 step 2 스캔 윈도우 상한으로 처리. 상태 변경 없이 로그만 남긴다 (ADR-L5, 후속 #238).
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
		// PG 조회: 트랜잭션 경계 밖에서 수행 (ADR-L1)
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
				return PaymentReconcileOutcome.SKIPPED;
			}
			default -> {
				log.warn("예상치 못한 대사 flow={} paymentId={}", flow, payment.getId());
				return PaymentReconcileOutcome.SKIPPED;
			}
		}
	}

	private PaymentReconcileOutcome executeApprove(Payment payment, LocalDateTime now, NaverPayHistoryResult historyResult) {
		try {
			// 대사도 실시간 승인과 동일하게 PG history의 merchantPayKey/금액을 검증한다 (M1, 비대칭 제거)
			payment.verifyApprovedResponse(historyResult.getMerchantPayKey(), historyResult.getTotalPayAmount());
			paymentApprovalService.succeedApproval(payment, now);
			log.info("대사 승인 확정 paymentId={} orderId={} merchantPayKey={}",
				payment.getId(), payment.getOrderId(), payment.getMerchantPayKey());
			return PaymentReconcileOutcome.SUCCEEDED;
		} catch (OrderException ex) {
			if (ex.getErrorCode() == OrderErrorCode.ORDER_PAID_NOT_ALLOWED) {
				return handleOrderNotCompletable(payment, now);
			}
			throw ex;
		} catch (PaymentException ex) {
			// 중복 결제: succeedApproval이 order.completePayment() 도달 전에 saveApproved의
			// uk_payment_approved_order_key 위반으로 PAYMENT_DUPLICATE를 던진다(order는 다른 결제로 이미 PAID).
			if (ex.getErrorCode() == PaymentErrorCode.PAYMENT_DUPLICATE) {
				return handleOrderNotCompletable(payment, now);
			}
			// M1: PG history와 merchantPayKey/금액 불일치 → 실시간과 동일하게 보상하고 FAILED로 종착
			if (ex.getErrorCode() == PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH) {
				paymentApprovalCompensationService.compensateMerchantKeyMismatch(payment);
				log.warn("대사 승인 - merchantPayKey 불일치 보상 paymentId={} orderId={} merchantPayKey={}",
					payment.getId(), payment.getOrderId(), payment.getMerchantPayKey());
				return PaymentReconcileOutcome.FAILED;
			}
			if (ex.getErrorCode() == PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH) {
				paymentApprovalCompensationService.compensateAmountMismatch(
					payment, historyResult.getTotalPayAmount(), this::pgCancelForReconciliation);
				log.warn("대사 승인 - 금액 불일치 보상 paymentId={} orderId={} merchantPayKey={}",
					payment.getId(), payment.getOrderId(), payment.getMerchantPayKey());
				return PaymentReconcileOutcome.FAILED;
			}
			throw ex;
		}
	}

	// succeedApproval이 ORDER_PAID_NOT_ALLOWED로 거부된 경우: 주문 상태를 재조회해 분기한다 (ADR-L4, ADR-L9).
	// 어떤 경로든 종착 상태(SUCCEEDED/FAILED)로 전이해 다음 주기에 재스캔되지 않도록 한다.
	private PaymentReconcileOutcome handleOrderNotCompletable(Payment payment, LocalDateTime now) {
		Order order = orderRepository.findById(payment.getOrderId()).orElse(null);

		if (order == null) {
			// 주문 자체가 없음 — 정합성 깨짐. 재시도해도 복구 불가이므로 종착시킨다.
			log.error("대사 - 주문 없음(정합성 오류) paymentId={} orderId={} merchantPayKey={}",
				payment.getId(), payment.getOrderId(), payment.getMerchantPayKey());
			paymentApprovalRecordService.fail(
				payment.getMerchantPayKey(), payment.getProvider(), payment.getPgPaymentId(),
				PaymentFailCode.APPROVE_PROCESS_FAILED, "주문 없음 - 정합성 오류", now
			);
			return PaymentReconcileOutcome.FAILED;
		}

		if (order.getStatus() == OrderStatus.CANCELED) {
			// C: CANCELED 주문 — 보상 취소(환불) + 통지 (ADR-L4)
			paymentApprovalCompensationService.compensateCanceledOrderApproval(payment, this::pgCancelForReconciliation);
			log.error("대사 보상 취소 실행 paymentId={} orderId={} merchantPayKey={}",
				payment.getId(), payment.getOrderId(), payment.getMerchantPayKey());
			return PaymentReconcileOutcome.FAILED;
		}

		if (order.getStatus() == OrderStatus.PAID) {
			// PAID: 이 결제가 성공 주체인지 vs 중복 결제인지 판별한다 (ADR-L9).
			boolean hasDuplicateSucceeded = paymentRepository.existsApprovedByOrderId(payment.getOrderId());
			if (hasDuplicateSucceeded) {
				// 다른 SUCCEEDED APPROVE가 이미 있음 — 이 건은 중복 결제, 보상(환불)한다.
				log.error("대사 PAID 주문 중복 결제 - 보상 paymentId={} orderId={} merchantPayKey={}",
					payment.getId(), payment.getOrderId(), payment.getMerchantPayKey());
				paymentApprovalCompensationService.compensateDuplicatePayment(
					payment,
					new RuntimeException("PAID 주문에 중복 결제 확인"),
					this::pgCancelForReconciliation
				);
				return PaymentReconcileOutcome.FAILED;
			}
			// 이 건이 성공 주체 — 주문은 이미 PAID이므로 결제 기록만 SUCCEEDED로 맞춘다.
			paymentApprovalService.succeedApprovalRecordOnly(payment, now);
			log.info("대사 PAID 주문 성공 주체 - 결제 기록 SUCCEEDED 확정 paymentId={} orderId={} merchantPayKey={}",
				payment.getId(), payment.getOrderId(), payment.getMerchantPayKey());
			return PaymentReconcileOutcome.SUCCEEDED;
		}

		// 그 외 비-INIT 상태(RECEIVED/COMPLETED 등) — 자동 대사로 해소 불가, 종착시킨다.
		log.error("대사 - 비-INIT 주문 상태로 종착 paymentId={} orderId={} orderStatus={} merchantPayKey={}",
			payment.getId(), payment.getOrderId(), order.getStatus(), payment.getMerchantPayKey());
		paymentApprovalRecordService.fail(
			payment.getMerchantPayKey(), payment.getProvider(), payment.getPgPaymentId(),
			PaymentFailCode.APPROVE_PROCESS_FAILED, "비-INIT 주문 상태: " + order.getStatus(), now
		);
		return PaymentReconcileOutcome.FAILED;
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
		paymentApprovalRecordService.fail(
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
