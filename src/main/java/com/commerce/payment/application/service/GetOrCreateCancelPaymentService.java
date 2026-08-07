package com.commerce.payment.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class GetOrCreateCancelPaymentService {

	private final PaymentRepository paymentRepository;

	/**
	 * 호출자 트랜잭션이 있으면 합류한다 — CANCEL 행의 커밋 시점은 호출자의 트랜잭션 경계다.
	 * 별도 트랜잭션으로 먼저 커밋되면 주문이 취소되지 않은 채 환불 의도만 남고, 대사가 그것을 환불로 집행한다.
	 */
	@Transactional
	public Payment getOrCreate(
		Long orderId,
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		int cancelAmount
	) {
		return paymentRepository.findCancelPayment(merchantPayKey, provider, pgPaymentId)
			.map(existing -> {
				if (existing.getAmount() != cancelAmount) {
					log.warn("Payment cancel amount mismatch - key={}, type=CANCEL, existingAmount={}, requested={}",
						merchantPayKey, existing.getAmount(), cancelAmount);
					throw new PaymentException(PaymentErrorCode.PAYMENT_RECORD_AMOUNT_MISMATCH);
				}
				return existing;
			})
			.orElseGet(() -> paymentRepository.save(
				Payment.createCancelRequested(orderId, merchantPayKey, pgPaymentId, cancelAmount, provider)
			));
	}
}
