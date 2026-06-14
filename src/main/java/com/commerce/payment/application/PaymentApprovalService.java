package com.commerce.payment.application;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentApprovalService {

	private final OrderRepository orderRepository;
	private final PaymentRepository paymentRepository;

	/**
	 * 대사 중 주문이 이미 PAID인 경우(이 결제가 성공 주체), 결제 기록만 SUCCEEDED로 확정한다.
	 * 주문 상태는 건드리지 않는다 — order.completePayment()를 호출하지 않는다.
	 */
	@Transactional
	public Payment succeedApprovalRecordOnly(Payment approvePayment, LocalDateTime now) {
		Payment current = paymentRepository.findApprovePayment(
				approvePayment.getMerchantPayKey(), approvePayment.getProvider(), approvePayment.getPgPaymentId())
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_RECORD_NOT_FOUND));

		if (current.getStatus() == PaymentStatus.SUCCEEDED) {
			log.info("결제 승인 멱등 흡수 merchantPayKey={} pgPaymentId={}",
				current.getMerchantPayKey(), current.getPgPaymentId());
			return current;
		}

		current.succeed(now);
		paymentRepository.saveApproved(current);

		log.info("결제 승인 기록 확정 merchantPayKey={} orderId={}",
			current.getMerchantPayKey(), current.getOrderId());
		return current;
	}

	/**
	 * PG 승인 응답 수신 후 payment.succeed() + order.completePayment()를 한 트랜잭션으로 처리한다 (ADR-8).
	 * 주문 행을 PK로 잠가(findByIdForUpdate) 같은 주문의 동시 승인 반영을 직렬화한다.
	 */
	@Transactional
	public Payment succeedApproval(
		Payment approvePayment,
		LocalDateTime now
	) {
		Payment current = paymentRepository.findApprovePayment(
				approvePayment.getMerchantPayKey(), approvePayment.getProvider(), approvePayment.getPgPaymentId())
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_RECORD_NOT_FOUND));

		if (current.getStatus() == PaymentStatus.SUCCEEDED) {
			log.info("결제 승인 멱등 흡수 merchantPayKey={} provider={} pgPaymentId={} orderId={}",
				current.getMerchantPayKey(), current.getProvider(), current.getPgPaymentId(), current.getOrderId());
			return current;
		}

		// 주문 행을 PK로 잠가 같은 주문의 동시 승인 반영을 직렬화한다 (결제 행 락은 없음).
		Order order = orderRepository.findByIdForUpdate(current.getOrderId())
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

		current.succeed(now);
		paymentRepository.saveApproved(current);
		order.completePayment();
		orderRepository.save(order);

		log.info("결제 승인 완료 merchantPayKey={} provider={} pgPaymentId={} orderId={}",
			current.getMerchantPayKey(), current.getProvider(), current.getPgPaymentId(), order.getId());
		return current;
	}
}
