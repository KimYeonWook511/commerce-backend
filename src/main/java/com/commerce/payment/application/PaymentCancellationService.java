package com.commerce.payment.application;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCancellationService {

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
		paymentRepository.save(payment);
	}

	@Transactional
	public void fail(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		PaymentFailCode failCode,
		String failDetail,
		LocalDateTime respondedAt
	) {
		Payment payment = paymentRepository.findCancelPayment(merchantPayKey, provider, pgPaymentId)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_RECORD_NOT_FOUND));
		payment.fail(failCode, failDetail, respondedAt);
		paymentRepository.save(payment);
	}

	/**
	 * REQUESTED 상태일 때만 CANCEL 기록을 UNKNOWN 마킹. 그 외 상태이거나 이력이 없으면 조용히 skip한다.
	 * PG 취소 결과 불명(네트워크/서버오류/응답 해석 불가) 시 흔적을 보존해 대사 대상으로 남긴다 (#219).
	 * CANCEL 타입 UNKNOWN 은 existsUnknownByOrderId(APPROVE 한정) 에 잡히지 않아 주문 재결제를 차단하지 않는다.
	 */
	@Transactional
	public void markUnknownIfRequested(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		String failDetail,
		LocalDateTime respondedAt
	) {
		Payment payment = paymentRepository.findCancelPayment(merchantPayKey, provider, pgPaymentId).orElse(null);
		if (payment == null) {
			log.warn("Cancel payment not found, skipping unknown mark: merchantPayKey={}, pgPaymentId={}",
				merchantPayKey, pgPaymentId);
			return;
		}
		if (payment.getStatus() != PaymentStatus.REQUESTED) {
			log.warn("Cancel payment not in REQUESTED state, skipping unknown mark: merchantPayKey={}, pgPaymentId={}, status={}",
				merchantPayKey, pgPaymentId, payment.getStatus());
			return;
		}
		payment.markUnknown(failDetail, respondedAt);
		paymentRepository.save(payment);
	}
}
