package com.commerce.payment.application;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.domain.Order;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentApprovalService {

	private final OrderRepository orderRepository;
	private final PaymentRepository paymentRepository;
	private final PaymentAttemptService paymentAttemptService;

	public Optional<Payment> findPaymentByMerchantPayKey(String merchantPayKey) {
		return paymentRepository.findByMerchantPayKey(merchantPayKey);
	}

	public boolean isCompensationRequired(String merchantPayKey) {
		return findPaymentByMerchantPayKey(merchantPayKey).isEmpty();
	}

	@Transactional
	public Payment completeApprovedPayment(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		LocalDateTime approvedAt
	) {
		// 동일 주문은 같은 순서로 잠가서 주문/결제 락 경합을 줄인다.
		Order order = orderRepository.findByMerchantPayKeyForUpdate(merchantPayKey)
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

		// 이미 해당 결제로 성공한 payment가 있는지 확인 -> 해당 부분이 없으면 try-catch에서 failed처리가 되어버림
		Payment completedPayment = paymentRepository.findByMerchantPayKey(merchantPayKey)
			.map(payment -> validateCompletedPaymentOrThrow(payment, provider, pgPaymentId))
			.orElse(null);

		// 결제 시도 완료 처리
		paymentAttemptService.succeedApproveAttempt(merchantPayKey, provider, pgPaymentId, approvedAt);

		if (completedPayment != null) {
			return completedPayment; // 이미 해당 결제로 성공한 payment 반환
		}

		// 주문 결제 완료 처리
		order.completePayment();

		// 결제 최종 정보 저장
		return paymentRepository.save(
			Payment.createCompleted(order, provider, merchantPayKey, pgPaymentId, approvedAt)
		);
	}

	private Payment validateCompletedPaymentOrThrow(
		Payment payment,
		PaymentProvider provider,
		String pgPaymentId
	) {
		if (payment.getProvider() != provider) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE);
		}
		if (!pgPaymentId.equals(payment.getPgPaymentId())) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE);
		}
		if (payment.getStatus() != PaymentStatus.COMPLETED) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_STATUS_NOT_ALLOWED);
		}
		return payment;
	}

}
