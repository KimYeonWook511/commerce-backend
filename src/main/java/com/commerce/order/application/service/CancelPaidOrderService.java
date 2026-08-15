package com.commerce.order.application.service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderItem;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundReason;
import com.commerce.payment.domain.RefundRequester;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.repository.RefundRepository;
import com.commerce.stock.application.service.IncreaseStockService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제된 주문 취소를 한 트랜잭션으로 묶는다. 주문 취소·환불 의도·재고 복구가 함께 커밋된다.
 *
 * <p>주문과 결제를 함께 커밋하는 것은 aggregate당 한 트랜잭션이라는 통상 원칙과 어긋나지만, 나누면
 * 주문은 취소됐는데 되돌릴 근거가 아직 없는 순간이 생긴다. 대사는 환불 행이 있으면 집행하고 정당성을
 * 다시 묻지 않으므로, 그 전제가 이 트랜잭션 경계 위에 서 있다.
 *
 * <p>다른 트랜잭션 서비스를 부르지 않고 리포지토리와 도메인 객체를 직접 다룬다 — 환불을 "찾거나
 * 만드는" 별도의 문을 두면 트랜잭션 없이 불러 정당한 조건 없는 환불만 커밋될 수 있다.
 *
 * <p>결제사 호출은 이 트랜잭션에 들어오지 않는다. 행 락을 쥔 채 외부 응답을 기다리게 되기 때문이며,
 * 커밋 뒤에 흐름을 조립하는 자리가 부른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CancelPaidOrderService {

	private final OrderRepository orderRepository;
	private final PaymentRepository paymentRepository;
	private final RefundRepository refundRepository;
	private final IncreaseStockService increaseStockService;

	/**
	 * 결제된 주문을 취소하고 되돌릴 환불을 연다.
	 *
	 * <p>같은 요청 키로 다시 들어오면 앞서 만든 환불이 그대로 돌아오고 누적 환불액도 다시 오르지 않는다.
	 * 그 판정은 결제의 도메인 메서드가 하며, 이 자리는 기존 사건을 찾아 넘기기만 한다.
	 */
	@Transactional
	public CancelPaidOrderResult cancelPaidOrder(Long memberId, Long orderId, String idempotencyKey) {
		// 주문 행만 잠근다. 남의 주문 번호를 실으면 그 주문이 있는지조차 드러나지 않는다.
		Order order = orderRepository.findByIdAndMemberIdForUpdate(orderId, memberId)
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

		if (order.getStatus() != OrderStatus.PAID) {
			throw new OrderException(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED);
		}
		// 승인 결과를 모르는 결제가 걸려 있으면 얼마를 돌려줘야 하는지가 아직 정해지지 않았다.
		if (paymentRepository.existsUnknownByOrderId(orderId)) {
			throw new OrderException(OrderErrorCode.ORDER_REFUND_NOT_AVAILABLE);
		}

		Payment payment = paymentRepository.findSucceededByMemberIdAndOrderId(memberId, orderId)
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_REFUND_TARGET_NOT_FOUND));
		if (payment.getApprovedAmount() == null) {
			throw new OrderException(OrderErrorCode.ORDER_REFUND_NOT_AVAILABLE);
		}

		// 유일 제약이 결제·요청자·요청 키 셋이라 조회도 같은 범위로 좁힌다. 범위가 어긋나면 조회가
		// 못 찾은 것을 제약이 잡아, 안전망으로만 쓰기로 한 위반이 정상 흐름에서 터진다.
		Optional<Refund> existing = refundRepository.findByPaymentIdAndRequesterAndIdempotencyKey(
			payment.getId(), RefundRequester.MEMBER, idempotencyKey);

		order.cancel();
		Refund refund = payment.openRefund(
			existing, payment.getApprovedAmount(), RefundReason.ORDER_CANCELED, idempotencyKey);

		// 재고 복구가 이 묶음의 맨 뒤다. 앞에 두면 재고 행 락을 쥔 채 뒤 작업을 기다린다.
		restoreStock(order);

		orderRepository.save(order);
		Refund savedRefund = refundRepository.save(refund);
		// 누적 환불액이 올라 결제 버전이 바뀐다. 동시에 온 두 요청 중 진 쪽은 그 버전에서 충돌해
		// 자기 환불까지 함께 롤백된다.
		paymentRepository.save(payment);

		log.info("결제된 주문 취소 접수 orderId={} memberId={} paymentId={} refundId={} refundAmount={}",
			orderId, memberId, payment.getId(), savedRefund.getId(), savedRefund.getAmount());

		return new CancelPaidOrderResult(order, payment, savedRefund, payment.remainingRefundableAmount());
	}

	/**
	 * 이미 취소된 주문에 같은 요청 키가 다시 왔을 때 앞선 결과를 찾는다. 응답이 유실되어 회원이 다시
	 * 보낸 경우이며, 그 키로 만들어진 환불이 곧 이 요청의 결과다.
	 *
	 * <p>요청 키로 좁히므로 다른 요청이 만든 환불이 돌아오지 않는다. 조회 범위는 환불을 만들 때 쓰는
	 * 것과 같은 결제·요청자·요청 키 셋이다.
	 */
	@Transactional(readOnly = true)
	public Optional<CancelPaidOrderResult> findPreviousCancel(Order order, Long memberId, String idempotencyKey) {
		return paymentRepository.findSucceededByMemberIdAndOrderId(memberId, order.getId())
			.filter(payment -> payment.getApprovedAmount() != null)
			.flatMap(payment -> refundRepository
				.findByPaymentIdAndRequesterAndIdempotencyKey(
					payment.getId(), RefundRequester.MEMBER, idempotencyKey)
				.map(refund -> new CancelPaidOrderResult(
					order, payment, refund, payment.remainingRefundableAmount())));
	}

	private void restoreStock(Order order) {
		List<OrderItem> sortedItems = order.getOrderItems().stream()
			.sorted(Comparator.comparing(OrderItem::getProductId))
			.toList();
		sortedItems.forEach(item -> increaseStockService.increase(item.getProductId(), item.getQuantity()));
	}

	/**
	 * 커밋된 사실을 흐름 조립 자리에 넘기는 결과.
	 *
	 * @param remainingAmount 앞으로 더 취소할 수 있는 금액. 승인 금액에서 누적 환불액을 뺀 값이며,
	 *                        한도를 재는 것과 같은 계산이라 응답이 그것을 그대로 쓴다
	 */
	public record CancelPaidOrderResult(Order order, Payment payment, Refund refund, int remainingAmount) {
	}
}
