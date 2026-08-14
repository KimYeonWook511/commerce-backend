package com.commerce.payment.legacy.application.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.payment.legacy.domain.Payment;
import com.commerce.payment.legacy.domain.PaymentProvider;
import com.commerce.payment.legacy.domain.repository.PaymentRepository;
import com.commerce.payment.legacy.domain.exception.PaymentErrorCode;
import com.commerce.payment.legacy.domain.exception.PaymentException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SucceedCancelPaymentService {

	private final PaymentRepository paymentRepository;

	@Transactional
	public void succeed(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		LocalDateTime respondedAt
	) {
		Payment payment = paymentRepository.findCancelPayment(merchantPayKey, provider, pgPaymentId)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_RECORD_NOT_FOUND));
		payment.succeed(respondedAt);
		paymentRepository.saveChecked(payment);
	}
}
