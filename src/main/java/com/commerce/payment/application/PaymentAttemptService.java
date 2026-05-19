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
@Transactional(readOnly = true)
public class PaymentAttemptService {

	private final PaymentAttemptRepository paymentAttemptRepository;

	/**
	 * - 해당 메소드는 트랜잭션을 열지 않음 (Repository에 있는 @Transactional 사용)
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public PaymentAttempt getOrCreateApproveAttempt(
		String merchantPayKey,
		PaymentProvider provider,
		String paymentId,
		int amount
	) {
		return paymentAttemptRepository.findApproveAttempt(merchantPayKey, provider, paymentId)
			.map(existing -> {
				if (existing.getAmount() != amount) {
					log.warn("PaymentAttempt amount mismatch - key={}, type=APPROVE, existingAmount={}, requested={}",
						merchantPayKey, existing.getAmount(), amount);
					throw new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_AMOUNT_MISMATCH);
				}
				return existing;
			})
			.orElseGet(() -> paymentAttemptRepository.save(
				PaymentAttempt.createApproveRequested(merchantPayKey, paymentId, amount, provider)
			));
	}

	/**
	 * - 해당 메소드는 트랜잭션을 열지 않음 (Repository에 있는 @Transactional 사용)
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public PaymentAttempt getOrCreateCancelAttempt(
		String merchantPayKey,
		PaymentProvider provider,
		String paymentId,
		int cancelAmount
	) {
		return paymentAttemptRepository.findCancelAttempt(merchantPayKey, provider, paymentId)
			.map(existing -> {
				if (existing.getAmount() != cancelAmount) {
					log.warn("PaymentAttempt amount mismatch - key={}, type=CANCEL, existingAmount={}, requested={}",
						merchantPayKey, existing.getAmount(), cancelAmount);
					throw new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_AMOUNT_MISMATCH);
				}
				return existing;
			})
			.orElseGet(() -> paymentAttemptRepository.save(
				PaymentAttempt.createCancelRequested(merchantPayKey, paymentId, cancelAmount, provider)
			));
	}

	@Transactional
	public void succeedApproveAttempt(
		String merchantPayKey,
		PaymentProvider provider,
		String paymentId,
		LocalDateTime respondedAt
	) {
		PaymentAttempt attempt = paymentAttemptRepository.findApproveAttempt(merchantPayKey, provider, paymentId)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND));
		attempt.markApproveSucceeded(respondedAt);
	}

	@Transactional
	public void failApproveAttempt(
		String merchantPayKey,
		PaymentProvider provider,
		String paymentId,
		PaymentAttemptFailCode failCode,
		String failDetail,
		LocalDateTime respondedAt
	) {
		PaymentAttempt attempt = paymentAttemptRepository.findApproveAttempt(merchantPayKey, provider, paymentId)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND));
		attempt.markApproveFailed(failCode, failDetail, respondedAt);
	}

	/**
	 * 보상 흐름 전용: REQUESTED 상태일 때만 실패 처리하고, 그 외 상태이면 조용히 skip한다.
	 * 호출처(catch 블록)가 상태를 확인하거나 try-catch로 mark 예외를 잡지 않도록 의도를 캡슐화한다.
	 */
	@Transactional
	public void failApproveAttemptIfRequested(
		String merchantPayKey,
		PaymentProvider provider,
		String paymentId,
		PaymentAttemptFailCode failCode,
		String failDetail,
		LocalDateTime respondedAt
	) {
		PaymentAttempt attempt = paymentAttemptRepository.findApproveAttempt(merchantPayKey, provider, paymentId)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND));
		if (attempt.getStatus() != PaymentAttemptStatus.REQUESTED) {
			log.warn(
				"PaymentAttempt not in REQUESTED state, skipping fail mark: merchantPayKey={}, paymentId={}, status={}",
				merchantPayKey, paymentId, attempt.getStatus());
			return;
		}
		attempt.markApproveFailed(failCode, failDetail, respondedAt);
	}

	@Transactional
	public void succeedCancelAttempt(
		String merchantPayKey,
		PaymentProvider provider,
		String paymentId,
		LocalDateTime respondedAt
	) {
		PaymentAttempt attempt = paymentAttemptRepository.findCancelAttempt(merchantPayKey, provider, paymentId)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND));
		attempt.markCancelSucceeded(respondedAt);
	}

	@Transactional
	public void failCancelAttempt(
		String merchantPayKey,
		PaymentProvider provider,
		String paymentId,
		PaymentAttemptFailCode failCode,
		String failDetail,
		LocalDateTime respondedAt
	) {
		PaymentAttempt attempt = paymentAttemptRepository.findCancelAttempt(merchantPayKey, provider, paymentId)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND));
		attempt.markCancelFailed(failCode, failDetail, respondedAt);
	}
}
