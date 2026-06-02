package com.commerce.payment.application;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentAttemptFailCode;
import com.commerce.payment.domain.PaymentAttemptStatus;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.repository.PaymentAttemptRepository;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentApprovalAttemptService {

	private final PaymentAttemptRepository paymentAttemptRepository;

	/**
	 * - 해당 메소드는 트랜잭션을 열지 않음 (Repository에 있는 @Transactional 사용)
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public PaymentAttempt getOrCreate(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		int amount
	) {
		return paymentAttemptRepository.findApproveAttempt(merchantPayKey, provider, pgPaymentId)
			.map(existing -> {
				if (existing.getAmount() != amount) {
					log.warn("PaymentAttempt amount mismatch - key={}, type=APPROVE, existingAmount={}, requested={}",
						merchantPayKey, existing.getAmount(), amount);
					throw new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_AMOUNT_MISMATCH);
				}
				return existing;
			})
			.orElseGet(() -> paymentAttemptRepository.save(
				PaymentAttempt.createApproveRequested(merchantPayKey, pgPaymentId, amount, provider)
			));
	}

	@Transactional
	public void succeed(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		LocalDateTime respondedAt
	) {
		PaymentAttempt attempt = paymentAttemptRepository.findApproveAttempt(merchantPayKey, provider, pgPaymentId)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND));
		attempt.succeed(respondedAt);
	}

	@Transactional
	public void fail(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		PaymentAttemptFailCode failCode,
		String failDetail,
		LocalDateTime respondedAt
	) {
		PaymentAttempt attempt = paymentAttemptRepository.findApproveAttempt(merchantPayKey, provider, pgPaymentId)
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
		PaymentAttemptFailCode failCode,
		String failDetail,
		LocalDateTime respondedAt
	) {
		PaymentAttempt attempt = paymentAttemptRepository.findApproveAttempt(merchantPayKey, provider, pgPaymentId)
			.orElse(null);
		if (attempt == null) {
			log.warn(
				"PaymentAttempt not found, skipping fail mark: merchantPayKey={}, provider={}, pgPaymentId={}",
				merchantPayKey, provider, pgPaymentId);
			return;
		}
		if (attempt.getStatus() != PaymentAttemptStatus.REQUESTED) {
			log.warn(
				"PaymentAttempt not in REQUESTED state, skipping fail mark: merchantPayKey={}, pgPaymentId={}, status={}",
				merchantPayKey, pgPaymentId, attempt.getStatus());
			return;
		}
		attempt.fail(failCode, failDetail, respondedAt);
	}
}
