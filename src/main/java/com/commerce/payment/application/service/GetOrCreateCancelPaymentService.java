package com.commerce.payment.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
	 * - 해당 메소드는 트랜잭션을 열지 않음 (Repository에 있는 @Transactional 사용)
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
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
