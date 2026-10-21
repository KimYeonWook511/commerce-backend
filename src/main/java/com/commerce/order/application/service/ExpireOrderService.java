package com.commerce.order.application.service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.order.domain.OrderItem;
import com.commerce.outbox.stock.application.dto.StockRestoreOutboxCreateCommand;
import com.commerce.outbox.stock.application.service.StockRestoreOutboxCreateService;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentCloseCode;
import com.commerce.payment.domain.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제되지 않은 주문을 만료시킨다. 주문 취소·재고 복구 이벤트·그 주문의 승인 전 결제 종결이 한
 * 트랜잭션으로 커밋된다.
 *
 * <p>결제를 함께 종결하는 것이 이 경계의 이유다. 주문만 취소하면 결제창이 살아 있어 회원이 인증을 마치는
 * 순간 승인이 나가 돈이 빠졌다가 되돌려진다. 종결해 두면 승인 호출 직전 전이가 막혀 결제사를 부르지
 * 않는다. 커밋 뒤 이벤트로 넘기면 그 사이 결제창이 살아 있으므로 넘기지 않는다.
 *
 * <p>승인을 부른 뒤의 결제가 걸렸으면 만료하지 않는다. 종결할 수 있는지는 결제가 답하고 여기서는 그 답으로
 * 멈출지만 정한다 — 결제 상태로 분기하면 그 규칙의 사본이 생긴다.
 *
 * <p>이 트랜잭션은 배치 청크 트랜잭션에 참여하므로 커밋 경계가 주문 하나가 아니라 청크다. 낙관 락 충돌은
 * 잡지 않고 그대로 내보내 배치가 밖에서 재시도·건너뛰기로 받게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpireOrderService {

	private final OrderRepository orderRepository;
	private final PaymentRepository paymentRepository;
	private final StockRestoreOutboxCreateService stockRestoreOutboxCreateService;

	@Transactional
	public void expireOrder(Long orderId, LocalDateTime requestedAt) {
		Order order = orderRepository.findByIdWithItems(orderId)
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

		// 대상 조회가 이미 걸렀어도 다시 본다. 충돌 뒤 다시 판정할 때 새로 읽은 결제는 승인을 부른 뒤일 수 있다.
		Optional<Payment> activePayment = paymentRepository.findActiveByOrderId(orderId);
		if (activePayment.isPresent() && !closeWithOrder(activePayment.get())) {
			log.info("승인을 부른 뒤의 결제가 걸려 주문을 만료하지 않는다 orderId={} paymentId={}",
				orderId, activePayment.get().getId());
			return;
		}

		order.cancelBeforePayment();

		stockRestoreOutboxCreateService.createOutboxEvent(toStockRestoreOutboxCreateCommand(order, requestedAt));

		log.info("주문 만료 orderId={} itemCount={}", order.getId(), order.getOrderItems().size());
	}

	private boolean closeWithOrder(Payment payment) {
		if (!payment.tryExpire(PaymentCloseCode.ORDER_EXPIRED)) {
			return false;
		}
		// 충돌을 도메인 예외로 바꾸지 않는 저장이어야 배치가 선언한 재시도가 그 예외를 알아본다.
		paymentRepository.save(payment);
		log.info("주문 만료로 결제 종결 paymentId={} orderId={}", payment.getId(), payment.getOrderId());
		return true;
	}

	private StockRestoreOutboxCreateCommand toStockRestoreOutboxCreateCommand(Order order, LocalDateTime requestedAt) {
		Map<Long, Integer> mergedQuantities = new HashMap<>();
		for (OrderItem orderItem : order.getOrderItems()) {
			Long productId = orderItem.getProductId();
			mergedQuantities.merge(productId, orderItem.getQuantity(), Integer::sum);
		}

		List<StockRestoreOutboxCreateCommand.Item> items = mergedQuantities.entrySet().stream()
			.map(entry -> StockRestoreOutboxCreateCommand.Item.builder()
				.productId(entry.getKey())
				.quantity(entry.getValue())
				.build())
			.sorted(Comparator.comparing(StockRestoreOutboxCreateCommand.Item::getProductId))
			.toList();

		return StockRestoreOutboxCreateCommand.builder()
			.orderId(order.getId())
			.items(items)
			.requestedAt(requestedAt)
			.build();
	}
}
