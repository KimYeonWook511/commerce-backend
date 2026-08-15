package com.commerce.payment.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.domain.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 도메인 밖까지 바꾸는 유일한 트랜잭션 자리. 결제를 성공시키는 것과 주문을 완료하는 것이 한 번에
 * 커밋된다 — 나누면 돈은 나갔는데 주문이 안 잡힌 상태나 그 반대가 남는다.
 *
 * <p>결제 안에서 끝나는 전이는 여기 두지 않는다. 함께 두면 "여기 이미 주문을 만지네" 하고 둘째가 들어와
 * 이 파일이 밖에 닿는 자리라는 뜻을 잃는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentApprovalService {

	private final PaymentRepository paymentRepository;
	private final OrderRepository orderRepository;

	/**
	 * 승인을 받아들여 결제를 성공으로, 주문을 결제완료로 만든다. 승인 요청 흐름과 대사가 이 한 자리를
	 * 공유한다.
	 *
	 * <p>주문 식별자를 인자로 받아 <b>결제를 읽기 전에</b> 주문 행을 잠근다. 결제를 먼저 읽으면 잠금
	 * 뒤에도 그 값이 갱신되지 않아, 같은 결제의 확정이 겹쳤을 때 뒤에 온 쪽이 앞선 성공을 못 보고
	 * 이미 결제완료가 된 주문에 다시 완료를 건다 — 그 오인이 성공한 결제를 반려로 밀어낸다.
	 */
	@Transactional
	public void complete(Long id, Long orderId, int approvedAmount, String pgTransactionId) {
		Order order = orderRepository.findByIdForUpdate(orderId)
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

		Payment payment = paymentRepository.findById(id)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));

		if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
			log.info("승인 확정 멱등 흡수 paymentId={} orderId={}", id, orderId);
			return;
		}

		payment.succeed(approvedAmount, pgTransactionId);
		order.completePayment();
		paymentRepository.saveChecked(payment);
		orderRepository.save(order);

		log.info("승인 확정 paymentId={} orderId={} approvedAmount={}", id, orderId, approvedAmount);
	}
}
