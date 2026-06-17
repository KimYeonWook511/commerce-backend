package com.commerce.order.application.service;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.order.application.dto.OrderCancelRefundStatus;
import com.commerce.order.application.dto.OrderCancelResult;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderItem;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.payment.application.service.GetOrCreateCancelPaymentService;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.stock.application.service.IncreaseStockService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PAID 주문 취소의 원자적 단위작업 (ADR-L1).
 * 한 tx 안에서 환불 의도(CANCEL REQUESTED) 영속화 + order.cancel() + 재고 복구를 커밋한다.
 * PG 호출은 tx 밖(CancelOrderUseCase)에서 best-effort로 실행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CancelPaidOrderService {

	private final OrderRepository orderRepository;
	private final PaymentRepository paymentRepository;
	private final GetOrCreateCancelPaymentService getOrCreateCancelPaymentService;
	private final IncreaseStockService increaseStockService;

	/**
	 * PAID 주문을 취소한다.
	 * order FOR UPDATE → PAID 검증 → 환불 대상 조회 → CANCEL REQUESTED 영속화 → order.cancel() → 재고 복구 커밋.
	 *
	 * @return 취소된 주문 결과(환불 의도 REQUESTED 상태 — refundStatus는 usecase가 최종 반영)
	 */
	@Transactional
	public CancelPaidOrderTransactionResult cancelPaidOrder(Long memberId, Long orderId) {
		Order order = orderRepository.findByIdAndMemberIdForUpdateWithItems(orderId, memberId)
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

		// PAID 상태 검증: 이 service는 PAID 전용. INIT은 CancelOrderService 경유.
		if (order.getStatus() != com.commerce.order.domain.OrderStatus.PAID) {
			throw new OrderException(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED);
		}

		// UNKNOWN/REQUESTED APPROVE 가드: PG 상태가 불확실한 주문은 환불 불가
		if (paymentRepository.existsUnconfirmedApproveByOrderId(orderId)) {
			throw new OrderException(OrderErrorCode.ORDER_REFUND_NOT_AVAILABLE);
		}

		// 환불 대상(SUCCEEDED APPROVE) 조회: PAID ↔ SUCCEEDED APPROVE는 정합 전제
		Payment approvePayment = paymentRepository.findApproveSucceededByOrderId(orderId)
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_REFUND_TARGET_NOT_FOUND));

		// 환불 의도(CANCEL REQUESTED) 영속화 — order 잠금 안에서 멱등 생성(ADR-L5)
		// cancelAmount는 approve.amount 그대로 사용(전액, 멱등 금액 불일치 방지)
		Payment cancelPayment = getOrCreateCancelPaymentService.getOrCreate(
			approvePayment.getOrderId(),
			approvePayment.getMerchantPayKey(),
			approvePayment.getProvider(),
			approvePayment.getPgPaymentId(),
			approvePayment.getAmount()
		);

		// 주문 PAID → CANCELED 전이
		order.cancel();

		// 재고 전량 복구 (productId 정렬 순서 유지)
		List<OrderItem> sortedItems = order.getOrderItems().stream()
			.sorted(Comparator.comparing(OrderItem::getProductId))
			.toList();
		sortedItems.forEach(item ->
			increaseStockService.increase(item.getProductId(), item.getQuantity())
		);

		log.info("PAID 주문 취소 접수 orderId={} memberId={} merchantPayKey={} cancelPaymentId={}",
			orderId, memberId, cancelPayment.getMerchantPayKey(), cancelPayment.getId());

		return new CancelPaidOrderTransactionResult(order, cancelPayment);
	}

	/**
	 * tx 커밋 후 usecase에 넘기는 결과 컨테이너.
	 * cancel 결제는 best-effort PG 환불에 사용한다.
	 */
	public record CancelPaidOrderTransactionResult(Order order, Payment cancelPayment) {
		public OrderCancelResult toResult(OrderCancelRefundStatus refundStatus) {
			return OrderCancelResult.withRefundStatus(order, refundStatus);
		}
	}
}
