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
public class MarkUnknownApprovePaymentService {

	private final PaymentRepository paymentRepository;

	/**
	 * APPROVE 결제 이력을 UNKNOWN으로 전이하는 transition (별도 빈의 public @Transactional).
	 * find → 도메인 전이(markUnknown 가드: REQUESTED 아니면 PAYMENT_STATUS_TRANSITION_NOT_ALLOWED) → saveChecked.
	 * PG 호출 timeout / 네트워크 단절 시 결과 불명 흔적을 보존한다.
	 * 충돌·가드 위반을 catch하지 않고 전파한다 — skip 판단은 useCase의 private 래퍼(트랜잭션 경계 밖)가 담당한다.
	 */
	@Transactional
	public void markUnknown(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		String failDetail,
		LocalDateTime respondedAt
	) {
		Payment payment = paymentRepository.findApprovePayment(merchantPayKey, provider, pgPaymentId)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_RECORD_NOT_FOUND));
		payment.markUnknown(failDetail, respondedAt);
		paymentRepository.saveChecked(payment);
	}
}
