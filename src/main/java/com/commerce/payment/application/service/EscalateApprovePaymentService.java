package com.commerce.payment.application.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EscalateApprovePaymentService {

	private final PaymentRepository paymentRepository;

	/**
	 * APPROVE 결제 이력을 escalation 표시하는 transition (별도 빈의 public @Transactional).
	 * find → 도메인 가드(escalate) → saveChecked. escalation 대상이 아니면(이미 escalation됐거나 종착) no-op으로 false를 반환한다.
	 * 가드 통과해 escalatedAt을 기록·저장하면 이 트랜잭션이 통지 주체이므로 true를 반환한다.
	 * 동시 시도 중 진 쪽은 saveChecked가 PAYMENT_CONCURRENTLY_MODIFIED를 던지고, skip(통지 안 함)은 useCase가 담당한다.
	 */
	@Transactional
	public boolean escalate(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		LocalDateTime now
	) {
		Payment payment = paymentRepository.findApprovePayment(merchantPayKey, provider, pgPaymentId)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_RECORD_NOT_FOUND));
		if (!payment.escalate(now)) {
			return false;
		}
		paymentRepository.saveChecked(payment);
		return true;
	}
}
