package com.commerce.payment.application;

import java.time.LocalDateTime;

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
public class PaymentCancellationAttemptService {

	private final PaymentAttemptRepository paymentAttemptRepository;

	/**
	 * - 해당 메소드는 트랜잭션을 열지 않음 (Repository에 있는 @Transactional 사용)
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public PaymentAttempt getOrCreate(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		int cancelAmount
	) {
		return paymentAttemptRepository.findCancelAttempt(merchantPayKey, provider, pgPaymentId)
			.map(existing -> {
				if (existing.getAmount() != cancelAmount) {
					log.warn("PaymentAttempt amount mismatch - key={}, type=CANCEL, existingAmount={}, requested={}",
						merchantPayKey, existing.getAmount(), cancelAmount);
					throw new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_AMOUNT_MISMATCH);
				}
				return existing;
			})
			.orElseGet(() -> paymentAttemptRepository.save(
				PaymentAttempt.createCancelRequested(merchantPayKey, pgPaymentId, cancelAmount, provider)
			));
	}

	@Transactional
	public void succeed(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		LocalDateTime respondedAt
	) {
		PaymentAttempt attempt = paymentAttemptRepository.findCancelAttempt(merchantPayKey, provider, pgPaymentId)
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
		PaymentAttempt attempt = paymentAttemptRepository.findCancelAttempt(merchantPayKey, provider, pgPaymentId)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND));
		attempt.fail(failCode, failDetail, respondedAt);
	}
}
