package com.commerce.order.application.usecase;

import org.springframework.stereotype.Component;

import com.commerce.order.application.dto.OrderCancelRefundStatus;
import com.commerce.order.application.dto.OrderCancelResult;
import com.commerce.order.application.service.CancelOrderService;
import com.commerce.order.application.service.CancelPaidOrderService;
import com.commerce.order.application.service.CancelPaidOrderService.CancelPaidOrderTransactionResult;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.payment.application.port.result.CancelOutcome;
import com.commerce.payment.application.usecase.RefundExecutionUseCase;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.naverpay.application.port.NaverPayGateway;
import com.commerce.payment.naverpay.application.port.result.NaverPayCancelResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 사용자 주도 주문 취소 흐름 조율 (ADR-L1·L2·L3, tx 없음).
 * 주문 상태에 따라 INIT(기존: 재고만 복구)과 PAID(환불 포함) 경로를 선택한다.
 * PAID 경로: 조율 service(tx) 호출 → 커밋 후 best-effort PG 환불 실행 → 결과를 응답에 반영.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CancelOrderUseCase {

	private final OrderRepository orderRepository;
	private final CancelOrderService cancelOrderService;
	private final CancelPaidOrderService cancelPaidOrderService;
	private final RefundExecutionUseCase refundExecutionUseCase;
	private final NaverPayGateway naverPayGateway;

	/**
	 * 주문을 취소한다. INIT은 기존 경로(재고 복구만), PAID는 환불 포함 경로.
	 */
	public OrderCancelResult cancel(Long memberId, Long orderId) {
		// 상태 조회 (잠금 없이): PAID 여부를 먼저 확인해 경로를 선택한다.
		// PAID 경로의 실제 검증·잠금은 CancelPaidOrderService(tx) 안에서 다시 수행한다.
		Order order = orderRepository.findByIdAndMemberId(orderId, memberId)
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

		if (order.getStatus() == OrderStatus.PAID) {
			return cancelPaidOrder(memberId, orderId);
		}

		// INIT 및 기타 상태 — 기존 CancelOrderService에 위임 (INIT만 허용, 나머지는 도메인에서 거부)
		return cancelOrderService.cancelOrder(memberId, orderId);
	}

	/**
	 * PAID 취소 흐름: tx 커밋(환불 의도 + order.cancel() + 재고 복구) → best-effort PG 환불.
	 */
	private OrderCancelResult cancelPaidOrder(Long memberId, Long orderId) {
		// 1. 원자적 단위작업: tx 안에서 환불 의도 영속화 + 취소 + 재고 복구
		CancelPaidOrderTransactionResult txResult = cancelPaidOrderService.cancelPaidOrder(memberId, orderId);

		// 2. 커밋 이후 best-effort PG 환불 실행 (ADR-L1, ADR-L2, ADR-L3)
		//    실패·UNKNOWN은 CANCEL 대사(step2)가 마무리한다.
		//    execute()가 PG 최종 결과를 반환한다. in-memory cancelPayment 상태에 의존하지 않는다.
		CancelOutcome cancelOutcome = refundExecutionUseCase.execute(txResult.cancelPayment(), this::pgCancel);

		// 3. PG 환불 결과에 따라 응답 refundStatus 결정
		OrderCancelRefundStatus refundStatus = resolveRefundStatus(cancelOutcome);

		log.info("주문 취소 완료 orderId={} memberId={} refundStatus={}", orderId, memberId, refundStatus);

		return txResult.toResult(refundStatus);
	}

	private CancelOutcome pgCancel(Payment cancelPayment, String reason) {
		NaverPayCancelResult result = naverPayGateway.cancel(
			cancelPayment.getPgPaymentId(), cancelPayment.getAmount(), reason
		);
		return switch (result.getStatus()) {
			case SUCCESS, ALREADY_CANCELED -> CancelOutcome.success();
			case PROCESSING -> CancelOutcome.processing();
			case FAILED -> CancelOutcome.failed(result.getFailCode(), result.getFailDetail());
			case UNKNOWN -> CancelOutcome.unknown(result.getFailDetail());
		};
	}

	/**
	 * PG 환불 실행 결과(CancelOutcome)를 refundStatus로 변환한다.
	 * execute()가 반환한 PG 결과를 직접 사용해, in-memory cancelPayment 상태에 의존하지 않는다.
	 */
	private OrderCancelRefundStatus resolveRefundStatus(CancelOutcome outcome) {
		return switch (outcome.status()) {
			case SUCCESS -> OrderCancelRefundStatus.COMPLETED;
			default -> OrderCancelRefundStatus.IN_PROGRESS;
		};
	}
}
