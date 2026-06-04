package com.commerce.payment.application;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentApprovalService {

	private final OrderRepository orderRepository;
	private final PaymentRepository paymentRepository;

	@Transactional(readOnly = true)
	public boolean hasCompletedPayment(String merchantPayKey) {
		return paymentRepository.existsApproveSucceeded(merchantPayKey);
	}

	/**
	 * PG 승인 응답 수신 후 attempt.succeed() + order.completePayment()를 한 트랜잭션으로 처리한다 (ADR-8).
	 * 동일 주문은 같은 순서로 잠가서 주문/결제 락 경합을 줄인다.
	 */
	@Transactional
	public Payment succeedApproval(
		Payment attempt,
		LocalDateTime now
	) {
		Payment current = paymentRepository.findApproveAttempt(
				attempt.getMerchantPayKey(), attempt.getProvider(), attempt.getPgPaymentId())
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND));

		if (current.getStatus() == PaymentStatus.SUCCEEDED) {
			log.info("결제 승인 멱등 흡수 merchantPayKey={} provider={} pgPaymentId={} orderId={}",
				current.getMerchantPayKey(), current.getProvider(), current.getPgPaymentId(), current.getOrderId());
			return current;
		}

		// 동일 주문은 같은 순서로 잠가서 주문/결제 락 경합을 줄인다.
		Order order = orderRepository.findByIdForUpdate(current.getOrderId())
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

		current.succeed(now);
		paymentRepository.save(current);
		order.completePayment();
		orderRepository.save(order);

		log.info("결제 승인 완료 merchantPayKey={} provider={} pgPaymentId={} orderId={}",
			current.getMerchantPayKey(), current.getProvider(), current.getPgPaymentId(), order.getId());
		return current;
	}
}
