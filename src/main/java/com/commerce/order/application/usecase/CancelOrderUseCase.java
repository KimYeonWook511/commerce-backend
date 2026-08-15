package com.commerce.order.application.usecase;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.commerce.common.exception.CommonErrorCode;
import com.commerce.common.exception.CommonException;
import com.commerce.order.application.dto.OrderCancelRefundStatus;
import com.commerce.order.application.dto.OrderCancelResult;
import com.commerce.order.application.port.OrderIdempotencyStore;
import com.commerce.order.application.service.CancelOrderService;
import com.commerce.order.application.service.CancelPaidOrderService;
import com.commerce.order.application.service.CancelPaidOrderService.CancelPaidOrderResult;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.infrastructure.OrderIdempotencyStoreUnavailableException;
import com.commerce.payment.application.port.dto.PgCallSource;
import com.commerce.payment.application.usecase.ExecuteRefundUseCase;
import com.commerce.payment.domain.RefundStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 회원이 자기 주문을 취소하는 흐름을 조립한다. 트랜잭션을 열지 않는다 — 결제사 호출이 트랜잭션 안에
 * 있으면 행 락을 쥔 채 외부 응답을 기다리게 된다.
 *
 * <p>순서가 정해져 있다 — 멱등키 선점, 취소 트랜잭션 커밋, 그 뒤 결제사 환불 호출이다. 커밋 뒤에
 * 부르므로 그 호출이 실패해도 주문 취소와 환불 의도는 남고, 발송 배치가 그 환불을 다시 보낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CancelOrderUseCase {

	private final OrderRepository orderRepository;
	private final OrderIdempotencyStore orderIdempotencyStore;
	private final CancelOrderService cancelOrderService;
	private final CancelPaidOrderService cancelPaidOrderService;
	private final ExecuteRefundUseCase executeRefundUseCase;

	@Value("${order.idempotency.ttl-seconds:60}")
	private long idempotencyTtlSeconds;

	/**
	 * 주문을 취소한다. 결제 전 주문은 재고만 돌려주고, 결제된 주문은 환불까지 잇는다.
	 *
	 * <p>선점을 못 잡은 쪽에게 "같은 요청이 처리 중"을 돌려준다. 그 창은 주문 취소·환불 의도·재고 복구를
	 * 한 번에 커밋하는 동안이라 넓고, 진 쪽이 서버 오류를 받으면 돈은 돌아가는 중인데 회원은 취소가 안 된
	 * 줄 알고 다시 요청한다.
	 */
	public OrderCancelResult cancel(Long memberId, Long orderId, String idempotencyKey) {
		if (!StringUtils.hasText(idempotencyKey)) {
			throw new CommonException(CommonErrorCode.INVALID_REQUEST);
		}

		boolean reserved;
		try {
			reserved = orderIdempotencyStore.reserveCancel(
				orderId, idempotencyKey, Duration.ofSeconds(idempotencyTtlSeconds));
		} catch (OrderIdempotencyStoreUnavailableException ex) {
			// 선점 저장소가 죽으면 DB 유일 제약 경로로 물러난다. 표시를 만들지 못했으므로 해제하지 않는다.
			log.warn("주문 취소 선점 저장소 장애, DB 유일 제약으로 물러난다: orderId={}, key={}", orderId, idempotencyKey);
			return execute(memberId, orderId, idempotencyKey);
		}

		if (!reserved) {
			throw new OrderException(OrderErrorCode.ORDER_CANCEL_IN_PROGRESS);
		}

		try {
			return execute(memberId, orderId, idempotencyKey);
		} finally {
			// 트랜잭션을 열지 않는 계층이라 이 finally 는 취소 트랜잭션이 끝난 뒤에 돈다.
			orderIdempotencyStore.clearCancel(orderId, idempotencyKey);
		}
	}

	private OrderCancelResult execute(Long memberId, Long orderId, String idempotencyKey) {
		// 잠그지 않고 상태만 읽어 경로를 고른다. 결제된 주문 취소의 검증과 잠금은 그 트랜잭션 안에서
		// 다시 한다.
		Order order = orderRepository.findByIdAndMemberId(orderId, memberId)
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

		if (order.getStatus() != OrderStatus.PAID) {
			return cancelOrderService.cancelOrder(memberId, orderId);
		}
		return cancelPaidOrder(memberId, orderId, idempotencyKey);
	}

	private OrderCancelResult cancelPaidOrder(Long memberId, Long orderId, String idempotencyKey) {
		CancelPaidOrderResult canceled = commitCancel(memberId, orderId, idempotencyKey);

		OrderCancelRefundStatus refundStatus = OrderCancelRefundStatus.from(sendRefund(canceled));
		log.info("주문 취소 완료 orderId={} memberId={} refundStatus={}", orderId, memberId, refundStatus);

		return OrderCancelResult.withRefund(
			canceled.order(), refundStatus, canceled.refund().getAmount(), canceled.remainingAmount());
	}

	/**
	 * 유일 제약이 선점 층의 안전망이다. 부딪혔다는 것은 같은 요청을 다른 쪽이 먼저 잡았다는 뜻이라 회원이
	 * 할 일이 같고, 그래서 응답도 선점에 진 쪽과 같다. 서버 오류로 내보내지 않는다.
	 */
	private CancelPaidOrderResult commitCancel(Long memberId, Long orderId, String idempotencyKey) {
		try {
			return cancelPaidOrderService.cancelPaidOrder(memberId, orderId, idempotencyKey);
		} catch (DataIntegrityViolationException ex) {
			log.info("주문 취소가 유일 제약에 막힘 orderId={} memberId={}", orderId, memberId);
			throw new OrderException(OrderErrorCode.ORDER_CANCEL_IN_PROGRESS);
		}
	}

	/**
	 * 커밋 뒤에 결제사를 부른다. 부르지 못한 채 끝나도 취소는 성공이고 환불 진행 상태만 "처리 중"으로
	 * 나간다 — 실패로 답하면 회원은 취소가 안 된 줄 알고 다시 요청하는데 주문은 이미 취소돼 있다.
	 */
	private RefundStatus sendRefund(CancelPaidOrderResult canceled) {
		try {
			return executeRefundUseCase.send(canceled.payment(), canceled.refund(), PgCallSource.MEMBER_REQUEST);
		} catch (RuntimeException ex) {
			log.error("주문 취소의 환불을 보내지 못해 발송 배치에 맡긴다 orderId={} refundId={}",
				canceled.order().getId(), canceled.refund().getId(), ex);
			return canceled.refund().getStatus();
		}
	}
}
