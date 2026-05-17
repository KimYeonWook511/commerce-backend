package com.commerce.payment.application;

import java.time.LocalDateTime;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentAttemptFailCode;
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
		try {
			return paymentAttemptRepository.save(
				PaymentAttempt.createApproveRequested(merchantPayKey, paymentId, amount, provider)
			);
		} catch (DataIntegrityViolationException ex) {
			PaymentAttempt existing = paymentAttemptRepository
				.findApproveAttempt(merchantPayKey, provider, paymentId)
				.orElseThrow(() -> {
					log.error("unique 충돌 후 approve attempt 재조회 실패: merchantPayKey={}, paymentId={}",
						merchantPayKey, paymentId, ex);
					return new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND);
				});
			if (existing.getAmount() != amount) {
				log.warn("PaymentAttempt amount mismatch — key={}, type=APPROVE, existing={}, requested={}",
					merchantPayKey, existing.getAmount(), amount);
				throw new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_AMOUNT_MISMATCH);
			}
			return existing;
		}
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
		try {
			return paymentAttemptRepository.save(
				PaymentAttempt.createCancelRequested(merchantPayKey, paymentId, cancelAmount, provider)
			);
		} catch (DataIntegrityViolationException ex) {
			PaymentAttempt existing = paymentAttemptRepository
				.findCancelAttempt(merchantPayKey, provider, paymentId)
				.orElseThrow(() -> {
					log.error("unique 충돌 후 cancel attempt 재조회 실패: merchantPayKey={}, paymentId={}",
						merchantPayKey, paymentId, ex);
					return new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND);
				});
			if (existing.getAmount() != cancelAmount) {
				log.warn("PaymentAttempt amount mismatch — key={}, type=CANCEL, existing={}, requested={}",
					merchantPayKey, existing.getAmount(), cancelAmount);
				throw new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_AMOUNT_MISMATCH);
			}
			return existing;
		}
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
