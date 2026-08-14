package com.commerce.payment.legacy.application.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.payment.legacy.domain.Payment;
import com.commerce.payment.legacy.domain.PaymentFailCode;
import com.commerce.payment.legacy.domain.PaymentProvider;
import com.commerce.payment.legacy.domain.repository.PaymentRepository;
import com.commerce.payment.legacy.domain.exception.PaymentErrorCode;
import com.commerce.payment.legacy.domain.exception.PaymentException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FailApprovePaymentService {

	private final PaymentRepository paymentRepository;

	/**
	 * APPROVE 결제 이력을 FAILED로 전이하는 transition (별도 빈의 public @Transactional).
	 * find → 도메인 전이(fail 가드) → saveChecked. 충돌도 가드 위반도 catch하지 않고 도메인 예외를 전파한다.
	 * 단조 종착(skip)이 필요한 호출처(보상/실시간)는 useCase의 private skip 래퍼에서 PAYMENT_CONCURRENTLY_MODIFIED·
	 * PAYMENT_STATUS_TRANSITION_NOT_ALLOWED·PAYMENT_RECORD_NOT_FOUND를 흡수한다(트랜잭션 경계 밖). 무조건 전이(대사)는 그대로 전파한다.
	 * 보상 대상 approve 는 실시간 경로에서는 REQUESTED, 대사 경로에서는 UNKNOWN 으로 진입하므로 둘 다 FAILED 로 확정한다.
	 */
	@Transactional
	public void fail(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		PaymentFailCode failCode,
		String failDetail,
		LocalDateTime respondedAt
	) {
		Payment payment = paymentRepository.findApprovePayment(merchantPayKey, provider, pgPaymentId)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_RECORD_NOT_FOUND));
		payment.fail(failCode, failDetail, respondedAt);
		paymentRepository.saveChecked(payment);
	}
}
