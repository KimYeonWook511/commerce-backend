package com.commerce.payment.legacy.application.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.payment.legacy.domain.Payment;
import com.commerce.payment.legacy.domain.PaymentProvider;
import com.commerce.payment.legacy.domain.PaymentType;
import com.commerce.payment.legacy.domain.exception.PaymentErrorCode;
import com.commerce.payment.legacy.domain.exception.PaymentException;
import com.commerce.payment.legacy.domain.repository.PaymentRepository;
import com.commerce.payment.legacy.postprocess.target.PaymentPostProcessTargetPolicy;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DelayPaymentReconcileService {

	private final PaymentRepository paymentRepository;

	/**
	 * KEEP_WAITING 판정 후 대사 재조회를 미룬다. next_reconcile_at = now + RECONCILE_BACKOFF.
	 * type에 따라 APPROVE/CANCEL finder를 선택해 양쪽 모두 일관되게 적용한다.
	 * status는 바꾸지 않는다.
	 */
	@Transactional
	public void delay(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		PaymentType type,
		LocalDateTime now
	) {
		Payment payment = (type == PaymentType.APPROVE
			? paymentRepository.findApprovePayment(merchantPayKey, provider, pgPaymentId)
			: paymentRepository.findCancelPayment(merchantPayKey, provider, pgPaymentId))
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_RECORD_NOT_FOUND));
		payment.delayReconcile(now, PaymentPostProcessTargetPolicy.RECONCILE_BACKOFF);
		paymentRepository.saveChecked(payment);
	}
}
