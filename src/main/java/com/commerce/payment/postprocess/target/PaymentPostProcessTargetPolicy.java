package com.commerce.payment.postprocess.target;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.PaymentStatus;

@Component
public class PaymentPostProcessTargetPolicy {

	// NaverPay 승인 가능 시간(10분)에서 파생. #208 운영 config 승격 전제.

	// UNKNOWN은 빨리 폴링 — capture 후 ack 유실인 경우 PG 조회가 즉시 APPROVED를 반환해 빠른 복구·차단 해제 가능
	// 대사 스캔 윈도우 하한과 단일 출처를 공유하기 위해 public (PaymentReconciliationService 참조)
	public static final Duration UNKNOWN_RECONCILE_DELAY = Duration.ofMinutes(1);

	// 승인 가능 시간(10분) + 마진(5분) — 윈도우가 닫힌 뒤에 reconcile해 AlreadyOnGoing 오판 방지
	// 대사 스캔 윈도우 REQUESTED 하한과 단일 출처를 공유하기 위해 public (PaymentReconciliationService 참조)
	public static final Duration REQUESTED_STALE_DELAY = Duration.ofMinutes(15);

	// reconcile 대상이 이 시간을 넘도록 PG가 결론을 못 내면 MANUAL 승급. 운영 config로 확정 예정
	// 대사 스캔 윈도우 상한과 단일 출처를 공유하기 위해 public (PaymentReconciliationService 참조)
	public static final Duration ESCALATION_DELAY = Duration.ofHours(6);

	public PaymentPostProcessTarget resolvePostProcessTarget(Payment approvePayment, Payment cancelPayment, LocalDateTime now) {
		if (approvePayment != null) {
			if (approvePayment.getStatus() == PaymentStatus.UNKNOWN) {
				if (hasElapsed(approvePayment.getRespondedAt(), ESCALATION_DELAY, now)) {
					return PaymentPostProcessTarget.MANUAL_REVIEW;
				}
				if (hasElapsed(approvePayment.getRespondedAt(), UNKNOWN_RECONCILE_DELAY, now)) {
					return PaymentPostProcessTarget.APPROVE_RECONCILE;
				}
			}
			if (approvePayment.getStatus() == PaymentStatus.REQUESTED) {
				if (hasElapsed(approvePayment.getCreatedAt(), ESCALATION_DELAY, now)) {
					return PaymentPostProcessTarget.MANUAL_REVIEW;
				}
				if (hasElapsed(approvePayment.getCreatedAt(), REQUESTED_STALE_DELAY, now)) {
					return PaymentPostProcessTarget.APPROVE_RECONCILE;
				}
			}
			if (approvePayment.getStatus() == PaymentStatus.FAILED) {
				if (approvePayment.getFailCode() == PaymentFailCode.MERCHANT_PAY_KEY_MISMATCH) {
					return PaymentPostProcessTarget.MANUAL_REVIEW;
				}
				if (isCancelCompensationRequired(approvePayment.getFailCode()) && cancelPayment == null) {
					return PaymentPostProcessTarget.APPROVED_CANCEL_COMPENSATION;
				}
			}
		}

		if (cancelPayment != null) {
			if (cancelPayment.getStatus() == PaymentStatus.UNKNOWN) {
				if (hasElapsed(cancelPayment.getRespondedAt(), ESCALATION_DELAY, now)) {
					return PaymentPostProcessTarget.MANUAL_REVIEW;
				}
				if (hasElapsed(cancelPayment.getRespondedAt(), UNKNOWN_RECONCILE_DELAY, now)) {
					return PaymentPostProcessTarget.CANCEL_RECONCILE;
				}
			}
			if (cancelPayment.getStatus() == PaymentStatus.REQUESTED) {
				if (hasElapsed(cancelPayment.getCreatedAt(), ESCALATION_DELAY, now)) {
					return PaymentPostProcessTarget.MANUAL_REVIEW;
				}
				if (hasElapsed(cancelPayment.getCreatedAt(), REQUESTED_STALE_DELAY, now)) {
					return PaymentPostProcessTarget.CANCEL_RECONCILE;
				}
			}
			if (cancelPayment.getStatus() == PaymentStatus.FAILED) {
				if (cancelPayment.getFailCode() == PaymentFailCode.PG_REQUEST_REJECTED) {
					return PaymentPostProcessTarget.MANUAL_REVIEW;
				}
				if (cancelPayment.getFailCode() == PaymentFailCode.CANCEL_PROCESS_FAILED
					|| cancelPayment.getFailCode() == PaymentFailCode.PG_INVALID_RESPONSE) {
					if (hasElapsed(cancelPayment.getRespondedAt(), ESCALATION_DELAY, now)) {
						return PaymentPostProcessTarget.MANUAL_REVIEW;
					}
					// FAILED는 PG 결과를 받은 상태라 하한 지연 없이 즉시 재조회한다 (UNKNOWN/REQUESTED의 진입 지연과 다름).
					// NaverPay 자동 재처리(CancelNotComplete)가 아직이면 대사가 PENDING → KEEP_WAITING으로 흡수한다.
					return PaymentPostProcessTarget.CANCEL_RECONCILE;
				}
			}
		}

		return PaymentPostProcessTarget.NONE;
	}

	private boolean isCancelCompensationRequired(PaymentFailCode failCode) {
		return failCode == PaymentFailCode.AMOUNT_MISMATCH
			|| failCode == PaymentFailCode.DUPLICATE_PAYMENT;
	}

	private boolean hasElapsed(LocalDateTime base, Duration delay, LocalDateTime now) {
		return !base.plus(delay).isAfter(now);
	}
}
