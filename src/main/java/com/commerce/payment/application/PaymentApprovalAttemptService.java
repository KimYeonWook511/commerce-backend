package com.commerce.payment.application;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.PaymentType;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.repository.PaymentReservationRepository;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentApprovalAttemptService {

	private final PaymentRepository paymentRepository;
	private final PaymentReservationRepository paymentReservationRepository;

	/**
	 * reservation.markUsed() + Payment 생성을 한 트랜잭션으로 묶어 원자성을 보장한다 (ADR-8).
	 * 재시도 시 attempt가 이미 존재하면 markUsed를 건너뛰고 기존 attempt를 반환한다.
	 */
	@Transactional
	public Payment create(PaymentReservation reservation, String pgPaymentId) {
		return paymentRepository.findApproveAttempt(
				reservation.getMerchantPayKey(), reservation.getProvider(), pgPaymentId)
			.orElseGet(() -> {
				reservation.markUsed();
				paymentReservationRepository.save(reservation);
				return paymentRepository.save(
					Payment.createRequested(reservation, PaymentType.APPROVE, pgPaymentId)
				);
			});
	}

	@Transactional
	public void fail(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		PaymentFailCode failCode,
		String failDetail,
		LocalDateTime respondedAt
	) {
		Payment attempt = paymentRepository.findApproveAttempt(merchantPayKey, provider, pgPaymentId)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND));
		attempt.fail(failCode, failDetail, respondedAt);
	}

	/**
	 * 보상 흐름 전용: REQUESTED 상태일 때만 실패 처리하고, 그 외 상태이거나 이력이 없으면 조용히 skip한다.
	 * 호출처(catch 블록)가 상태를 확인하거나 try-catch로 mark 예외를 잡지 않도록 의도를 캡슐화한다.
	 */
	@Transactional
	public void failIfRequested(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		PaymentFailCode failCode,
		String failDetail,
		LocalDateTime respondedAt
	) {
		Payment attempt = paymentRepository.findApproveAttempt(merchantPayKey, provider, pgPaymentId)
			.orElse(null);
		if (attempt == null) {
			log.warn(
				"Payment not found, skipping fail mark: merchantPayKey={}, provider={}, pgPaymentId={}",
				merchantPayKey, provider, pgPaymentId);
			return;
		}
		if (attempt.getStatus() != PaymentStatus.REQUESTED) {
			log.warn(
				"Payment not in REQUESTED state, skipping fail mark: merchantPayKey={}, pgPaymentId={}, status={}",
				merchantPayKey, pgPaymentId, attempt.getStatus());
			return;
		}
		attempt.fail(failCode, failDetail, respondedAt);
	}
}
