package com.commerce.order.application.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderCancelLine;
import com.commerce.order.domain.OrderCancelPlan;
import com.commerce.order.domain.OrderItem;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundReason;
import com.commerce.payment.domain.RefundRequester;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
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
	 * <p>같은 요청 키의 환불이 이미 있으면 앞선 결과를 그대로 돌려주고 주문·재고·결제 어느 것도 건드리지
	 * 않는다. 그 판정을 이 트랜잭션 안에서, 주문 행을 잠근 뒤에 한다 — 앞단의 선점 표시에는 유효 시간과
	 * 저장소 장애 시 물러나는 경로가 있어 두 요청이 함께 들어오는 창이 그것만으로는 닫히지 않는다.
	 */
	@Transactional
	public CancelPaidOrderResult cancelPaidOrder(
		Long memberId, Long orderId, String idempotencyKey, List<OrderCancelLine> requestedLines) {
		// 주문 행만 잠근다. 남의 주문 번호를 실으면 그 주문이 있는지조차 드러나지 않는다.
		Order order = orderRepository.findByIdAndMemberIdForUpdate(orderId, memberId)
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

		Optional<Payment> refundTarget = paymentRepository.findSucceededByMemberIdAndOrderId(memberId, orderId);
		Optional<Refund> existing = findExistingRefund(refundTarget, idempotencyKey);
		if (existing.isPresent()) {
			// 주문 상태를 보지 않고 판정한다. 일부만 취소돼 주문이 결제완료로 남아 있어도 같은 키의
			// 재요청이면 앞 결과를 받아야 하고, 그러지 않으면 취소가 한 번 더 반영된다.
			Payment refunded = refundTarget.orElseThrow();
			Refund previous = existing.get();
			// 금액이 같아도 품목 조합이 다를 수 있어 주문이 자기 내역으로 대조한다. 결제 쪽 금액·사유
			// 대조는 그대로 두어, 주문을 거치지 않는 경로가 생겼을 때의 안전망으로 남긴다.
			if (!order.matchesCancellation(previous.getId(), requestedLines)) {
				throw new PaymentException(PaymentErrorCode.REFUND_IDEMPOTENCY_KEY_CONFLICT);
			}
			log.info("같은 요청 키의 환불이 이미 있어 앞선 취소 결과를 돌려준다 orderId={} refundId={}",
				orderId, previous.getId());
			return CancelPaidOrderResult.replayed(
				order, refunded, previous, refunded.remainingRefundableAmount());
		}

		order.checkCancellable();
		// 승인 결과를 모르는 결제가 걸려 있으면 얼마를 돌려줘야 하는지가 아직 정해지지 않았다.
		if (paymentRepository.existsUnknownByOrderId(orderId)) {
			throw new OrderException(OrderErrorCode.ORDER_REFUND_NOT_AVAILABLE);
		}

		Payment payment = refundTarget
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_REFUND_TARGET_NOT_FOUND));
		if (payment.getApprovedAmount() == null) {
			throw new OrderException(OrderErrorCode.ORDER_REFUND_NOT_AVAILABLE);
		}

		// 무엇을 얼마나 취소할지는 주문이 정한다. 잔여수량이 저장되지 않는 파생값이라, 계산이 이 흐름으로
		// 나오면 도메인이 자기 불변식을 잃는다.
		OrderCancelPlan plan = order.planCancellation(requestedLines);
		// 승인 금액이 아니라 주문이 계산한 값어치로 환불을 연다. 찾아 둔 기존 사건을 그대로 넘기는 것은
		// 결제 쪽 내용 대조를 안전망으로 남기기 위해서다.
		Refund refund = payment.openRefund(
			existing, plan.cancelAmount(), RefundReason.ORDER_CANCELED, idempotencyKey);
		// 취소 품목 내역이 환불 식별자를 적으므로 환불을 먼저 저장해 그 값을 얻는다.
		Refund savedRefund = refundRepository.save(refund);

		order.applyCancellation(plan, savedRefund.getId());

		// 재고 복구가 이 묶음의 맨 뒤다. 앞에 두면 재고 행 락을 쥔 채 뒤 작업을 기다린다.
		restoreStock(order, plan);

		orderRepository.save(order);
		// 돌려주기로 한 금액이 올라 결제 버전이 바뀐다. 동시에 온 두 요청 중 진 쪽은 그 버전에서 충돌해
		// 자기 환불까지 함께 롤백된다.
		paymentRepository.save(payment);

		log.info("결제된 주문 취소 접수 orderId={} memberId={} paymentId={} refundId={} refundAmount={} lineCount={}",
			orderId, memberId, payment.getId(), savedRefund.getId(), savedRefund.getAmount(), plan.lines().size());

		return CancelPaidOrderResult.accepted(order, payment, savedRefund, payment.remainingRefundableAmount());
	}

	/**
	 * 같은 요청 키로 이미 만들어진 환불을 찾는다. 응답이 유실되어 회원이 다시 보낸 경우이며, 그 키로
	 * 만들어진 환불이 곧 이 요청의 결과다.
	 *
	 * <p>유일 제약이 결제·요청자·요청 키 셋이라 조회도 같은 범위로 좁힌다. 범위가 어긋나면 조회가
	 * 못 찾은 것을 제약이 잡아, 안전망으로만 쓰기로 한 위반이 정상 흐름에서 터진다.
	 */
	private Optional<Refund> findExistingRefund(Optional<Payment> refundTarget, String idempotencyKey) {
		return refundTarget
			.filter(payment -> payment.getApprovedAmount() != null)
			.flatMap(payment -> refundRepository.findByPaymentIdAndRequesterAndIdempotencyKey(
				payment.getId(), RefundRequester.MEMBER, idempotencyKey));
	}

	/**
	 * 이번에 취소한 품목만 골라 그 요청 수량만큼 재고를 돌려놓는다. 상품 식별자 오름차순으로 도는 것은
	 * 재고 행 교착을 피하는 계약이라 취소 대상이 줄어도 그대로 지킨다.
	 */
	private void restoreStock(Order order, OrderCancelPlan plan) {
		Map<Long, Integer> quantityByOrderItemId = plan.lines().stream()
			.collect(Collectors.toMap(OrderCancelLine::orderItemId, OrderCancelLine::quantity));

		order.getOrderItems().stream()
			.filter(item -> quantityByOrderItemId.containsKey(item.getId()))
			.sorted(Comparator.comparing(OrderItem::getProductId))
			.forEach(item -> increaseStockService.increase(
				item.getProductId(), quantityByOrderItemId.get(item.getId())));
	}

	/**
	 * 커밋된 사실을 흐름 조립 자리에 넘기는 결과.
	 *
	 * @param remainingAmount 앞으로 더 취소할 수 있는 금액. 승인 금액에서 돌려주기로 한 금액을 뺀 값이며,
	 *                        한도를 재는 것과 같은 계산이라 응답이 그것을 그대로 쓴다
	 * @param replayed        같은 요청 키의 환불이 이미 있어 앞선 결과를 그대로 돌려준 것인지. 참이면
	 *                        결제사를 부르지 않는다 — 그 환불은 이미 자기 경로로 나가 있다
	 */
	public record CancelPaidOrderResult(
		Order order, Payment payment, Refund refund, int remainingAmount, boolean replayed) {

		public static CancelPaidOrderResult accepted(
			Order order, Payment payment, Refund refund, int remainingAmount) {
			return new CancelPaidOrderResult(order, payment, refund, remainingAmount, false);
		}

		public static CancelPaidOrderResult replayed(
			Order order, Payment payment, Refund refund, int remainingAmount) {
			return new CancelPaidOrderResult(order, payment, refund, remainingAmount, true);
		}
	}
}
