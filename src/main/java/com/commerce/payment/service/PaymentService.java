package com.commerce.payment.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.order.domain.Order;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.order.repository.OrderRepository;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

	private final OrderRepository orderRepository;
	private final PaymentRepository paymentRepository;

	public Payment getPayment(Long orderId) {
		return paymentRepository.findByOrderId(orderId)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));
	}

	@Transactional
	public Payment createPayment(Long orderId, PaymentProvider provider) {
		Order order = orderRepository.findById(orderId)
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

		Payment payment = Payment.create(order, order.getTotalPrice(), provider);
		paymentRepository.save(payment);

		return payment;
	}

	@Transactional
	public Payment completePayment(Long orderId, LocalDateTime approvedAt) {
		Payment payment = getPayment(orderId);
		payment.complete(approvedAt);
		return payment;
	}

	@Transactional
	public Payment failPayment(Long orderId, String reason) {
		Payment payment = getPayment(orderId);
		payment.fail(reason);
		return payment;
	}

	@Transactional
	public Payment cancelPayment(Long orderId, String reason) {
		Payment payment = getPayment(orderId);
		payment.cancel(reason);
		return payment;
	}
}
