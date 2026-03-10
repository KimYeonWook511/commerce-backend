package com.commerce.payment.service;

import java.time.LocalDateTime;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentAttemptFailCode;
import com.commerce.payment.domain.PaymentAttemptType;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.repository.PaymentAttemptRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentAttemptService {

	private final PaymentAttemptRepository paymentAttemptRepository;

	/**
	 * - 해당 메소드는 트랜잭션을 열지 않음 (Repository에 있는 @Transactional 사용)
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public PaymentAttempt getOrCreateApproveRequested(
		String merchantPayKey,
		PaymentProvider provider,
		String paymentId,
		int amount
	) {
		try {
			return paymentAttemptRepository.saveAndFlush(
				PaymentAttempt.createApproveRequested(merchantPayKey, paymentId, amount, provider)
			);
		} catch (DataIntegrityViolationException ex) {
			return paymentAttemptRepository.findByMerchantPayKeyAndProviderAndPaymentIdAndType(
					merchantPayKey, provider, paymentId, PaymentAttemptType.APPROVE)
				.orElseThrow(() -> ex);
		}
	}

	/**
	 * - 해당 메소드는 트랜잭션을 열지 않음 (Repository에 있는 @Transactional 사용)
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public PaymentAttempt getOrCreateCancelRequested(
		String merchantPayKey,
		PaymentProvider provider,
		String paymentId,
		int cancelAmount
	) {
		try {
			return paymentAttemptRepository.saveAndFlush(
				PaymentAttempt.createCancelRequested(merchantPayKey, paymentId, cancelAmount, provider)
			);
		} catch (DataIntegrityViolationException ex) {
			return paymentAttemptRepository.findByMerchantPayKeyAndProviderAndPaymentIdAndType(
					merchantPayKey, provider, paymentId, PaymentAttemptType.CANCEL)
				.orElseThrow(() -> ex);
		}
	}

	@Transactional
	public void succeedApproveAttempt(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		LocalDateTime respondedAt
	) {
		PaymentAttempt attempt = paymentAttemptRepository.findByMerchantPayKeyAndProviderAndPaymentIdAndType(
				merchantPayKey, provider, pgPaymentId, PaymentAttemptType.APPROVE)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND));
		attempt.approveSucceed(respondedAt);
	}

	@Transactional
	public void failApproveAttempt(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		PaymentAttemptFailCode failCode,
		String failDetail,
		LocalDateTime respondedAt
	) {
		PaymentAttempt attempt = paymentAttemptRepository.findByMerchantPayKeyAndProviderAndPaymentIdAndType(
				merchantPayKey, provider, pgPaymentId, PaymentAttemptType.APPROVE)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND));
		attempt.approveFail(failCode, failDetail, respondedAt);
	}

	@Transactional
	public void succeedCancelAttempt(
		String merchantPayKey,
		PaymentProvider provider,
		String paymentId,
		LocalDateTime respondedAt
	) {
		PaymentAttempt attempt = paymentAttemptRepository.findByMerchantPayKeyAndProviderAndPaymentIdAndType(
				merchantPayKey, provider, paymentId, PaymentAttemptType.CANCEL)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND));
		attempt.cancelSucceed(respondedAt);
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
		PaymentAttempt attempt = paymentAttemptRepository.findByMerchantPayKeyAndProviderAndPaymentIdAndType(
				merchantPayKey, provider, paymentId, PaymentAttemptType.CANCEL)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND));
		attempt.failCancel(failCode, failDetail, respondedAt);
	}
}
