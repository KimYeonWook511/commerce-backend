package com.commerce.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.common.exception.CommonErrorCode;
import com.commerce.common.exception.CommonException;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;

/**
 * 부분취소의 잔여수량 판정·취소수량 반영·값어치 계산·상태 전이를 주문 도메인만으로 확인한다.
 * 기준 주문은 1만원 사과 3개와 2만원 배 1개로 5만원이다.
 */
class OrderCancellationTest {

	private static final long MEMBER_ID = 1L;
	private static final long APPLE_PRODUCT_ID = 10L;
	private static final long PEAR_PRODUCT_ID = 20L;
	private static final int APPLE_UNIT_PRICE = 10_000;
	private static final int PEAR_UNIT_PRICE = 20_000;
	private static final long APPLE_ITEM_ID = 101L;
	private static final long PEAR_ITEM_ID = 102L;
	private static final long REFUND_ID = 900L;

	@DisplayName("일부만 취소하면 남은 수량이 있어 주문이 결제 완료 상태를 유지한다")
	@Test
	void applyCancellation_whenSomeQuantityRemains_keepPaidStatus() {
		// Given
		Order order = paidOrder();

		// When
		cancel(order, List.of(new OrderCancelLine(APPLE_ITEM_ID, 1)), REFUND_ID);

		// Then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(appleItem(order).getCancelledQuantity()).isEqualTo(1);
		assertThat(appleItem(order).remainingQuantity()).isEqualTo(2);
		assertThat(pearItem(order).remainingQuantity()).isEqualTo(1);
	}

	@DisplayName("모든 품목의 잔여수량이 0이 되면 주문이 취소로 전이한다")
	@Test
	void applyCancellation_whenNoQuantityRemains_changeToCanceled() {
		// Given
		Order order = paidOrder();
		cancel(order, List.of(new OrderCancelLine(APPLE_ITEM_ID, 3)), REFUND_ID);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);

		// When
		cancel(order, List.of(new OrderCancelLine(PEAR_ITEM_ID, 1)), REFUND_ID + 1);

		// Then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
		assertThat(appleItem(order).remainingQuantity()).isZero();
		assertThat(pearItem(order).remainingQuantity()).isZero();
	}

	@DisplayName("취소 값어치는 상품의 현재 판매가가 아니라 주문 시점 단가로 계산된다")
	@Test
	void planCancellation_whenProductPriceChanged_useOrderTimeUnitPrice() {
		// Given
		Order order = paidOrder();

		// When
		OrderCancelPlan plan = order.planCancellation(List.of(new OrderCancelLine(APPLE_ITEM_ID, 1)));

		// Then
		assertThat(plan.cancelAmount()).isEqualTo(APPLE_UNIT_PRICE);
	}

	@DisplayName("잔여수량을 넘는 수량을 요청하면 거절되고 아무 상태도 바뀌지 않는다")
	@Test
	void planCancellation_whenQuantityExceedsRemaining_throwQuantityExceeded() {
		// Given
		Order order = paidOrder();
		cancel(order, List.of(new OrderCancelLine(APPLE_ITEM_ID, 1)), REFUND_ID);

		// When & Then
		assertThatThrownBy(() -> order.planCancellation(List.of(new OrderCancelLine(APPLE_ITEM_ID, 3))))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> assertThat(((OrderException)exception).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_CANCEL_QUANTITY_EXCEEDED));
		assertThat(appleItem(order).getCancelledQuantity()).isEqualTo(1);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
	}

	@DisplayName("한 품목만 잔여수량을 넘어도 요청 전체가 거절되어 다른 품목도 바뀌지 않는다")
	@Test
	void planCancellation_whenOneLineExceedsRemaining_rejectWholeRequest() {
		// Given
		Order order = paidOrder();
		cancel(order, List.of(new OrderCancelLine(APPLE_ITEM_ID, 1)), REFUND_ID);

		// When & Then
		assertThatThrownBy(() -> order.planCancellation(List.of(
			new OrderCancelLine(APPLE_ITEM_ID, 3),
			new OrderCancelLine(PEAR_ITEM_ID, 1)
		)))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> assertThat(((OrderException)exception).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_CANCEL_QUANTITY_EXCEEDED));
		assertThat(appleItem(order).getCancelledQuantity()).isEqualTo(1);
		assertThat(pearItem(order).getCancelledQuantity()).isZero();
	}

	@DisplayName("초과하는 줄이 뒤에 있어도 앞줄이 반영되지 않는다")
	@Test
	void applyCancellation_whenLaterLineExceedsRemaining_applyNoLine() {
		// Given
		Order order = paidOrder();
		OrderCancelPlan plan = order.planCancellation(List.of(
			new OrderCancelLine(PEAR_ITEM_ID, 1),
			new OrderCancelLine(APPLE_ITEM_ID, 3)
		));
		cancel(order, List.of(new OrderCancelLine(APPLE_ITEM_ID, 1)), REFUND_ID);

		// When & Then
		assertThatThrownBy(() -> order.applyCancellation(plan, REFUND_ID + 1))
			.isInstanceOf(OrderException.class);
		assertThat(pearItem(order).getCancelledQuantity()).isZero();
		assertThat(appleItem(order).getCancelledQuantity()).isEqualTo(1);
	}

	@DisplayName("이 주문에 없는 품목 식별자를 요청하면 잘못된 요청으로 거절된다")
	@Test
	void planCancellation_whenOrderItemNotInOrder_throwInvalidRequest() {
		// Given
		Order order = paidOrder();

		// When & Then
		assertThatThrownBy(() -> order.planCancellation(List.of(new OrderCancelLine(999L, 1))))
			.isInstanceOf(CommonException.class)
			.satisfies(exception -> assertThat(((CommonException)exception).getErrorCode())
				.isEqualTo(CommonErrorCode.INVALID_REQUEST));
		assertThat(appleItem(order).getCancelledQuantity()).isZero();
	}

	@DisplayName("수량이 0이거나 음수면 잘못된 요청으로 거절된다")
	@Test
	void planCancellation_whenQuantityNotPositive_throwInvalidRequest() {
		// Given
		Order order = paidOrder();

		// When & Then
		assertThatThrownBy(() -> order.planCancellation(List.of(new OrderCancelLine(APPLE_ITEM_ID, 0))))
			.isInstanceOf(CommonException.class)
			.satisfies(exception -> assertThat(((CommonException)exception).getErrorCode())
				.isEqualTo(CommonErrorCode.INVALID_REQUEST));
		assertThatThrownBy(() -> order.planCancellation(List.of(new OrderCancelLine(APPLE_ITEM_ID, -1))))
			.isInstanceOf(CommonException.class);
		assertThat(appleItem(order).getCancelledQuantity()).isZero();
	}

	@DisplayName("빈 목록을 넘기면 이미 다 취소된 품목은 빠지고 남은 품목만 취소 대상이 된다")
	@Test
	void planCancellation_whenLinesEmptyAndSomeItemFullyCancelled_targetOnlyRemainingItems() {
		// Given
		Order order = paidOrder();
		cancel(order, List.of(new OrderCancelLine(APPLE_ITEM_ID, 3)), REFUND_ID);

		// When
		OrderCancelPlan plan = order.planCancellation(List.of());
		order.applyCancellation(plan, REFUND_ID + 1);

		// Then
		assertThat(plan.lines()).containsExactly(new OrderCancelLine(PEAR_ITEM_ID, 1));
		assertThat(plan.cancelAmount()).isEqualTo(PEAR_UNIT_PRICE);
		assertThat(appleItem(order).getCancelledQuantity()).isEqualTo(3);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
	}

	@DisplayName("잔여가 하나도 없는 주문에 빈 목록을 넘기면 취소 대상이 없어 거절된다")
	@Test
	void planCancellation_whenNothingRemains_throwCancelNotAllowed() {
		// Given
		Order order = paidOrder();
		cancel(order, List.of(
			new OrderCancelLine(APPLE_ITEM_ID, 3),
			new OrderCancelLine(PEAR_ITEM_ID, 1)
		), REFUND_ID);

		// When & Then
		assertThatThrownBy(() -> order.planCancellation(List.of()))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> assertThat(((OrderException)exception).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED));
		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
		assertThat(appleItem(order).getCancelledQuantity()).isEqualTo(3);
	}

	@DisplayName("취소를 반영하면 어느 품목을 몇 개 취소했는지가 환불 식별자와 함께 남는다")
	@Test
	void applyCancellation_whenApplied_recordCancelledItemAndQuantity() {
		// Given
		Order order = paidOrder();

		// When
		cancel(order, List.of(new OrderCancelLine(APPLE_ITEM_ID, 2)), REFUND_ID);

		// Then
		List<OrderItemCancellation> cancellations = appleItem(order).getCancellations();
		assertThat(cancellations).hasSize(1);
		assertThat(cancellations.get(0).getRefundId()).isEqualTo(REFUND_ID);
		assertThat(cancellations.get(0).getQuantity()).isEqualTo(2);
		assertThat(cancellations.get(0).getOrderItem()).isEqualTo(appleItem(order));
	}

	@DisplayName("같은 품목을 나눠 취소하면 취소수량이 누적되고 내역이 요청마다 남는다")
	@Test
	void applyCancellation_whenCancelledTwice_accumulateCancelledQuantity() {
		// Given
		Order order = paidOrder();

		// When
		cancel(order, List.of(new OrderCancelLine(APPLE_ITEM_ID, 1)), REFUND_ID);
		cancel(order, List.of(new OrderCancelLine(APPLE_ITEM_ID, 2)), REFUND_ID + 1);

		// Then
		assertThat(appleItem(order).getCancelledQuantity()).isEqualTo(3);
		assertThat(appleItem(order).getCancellations()).hasSize(2);
		assertThat(appleItem(order).getCancellations().stream()
			.mapToInt(OrderItemCancellation::getQuantity)
			.sum()).isEqualTo(appleItem(order).getCancelledQuantity());
	}

	@DisplayName("총 주문 금액은 취소해도 줄어들지 않는다")
	@Test
	void applyCancellation_whenApplied_keepTotalPrice() {
		// Given
		Order order = paidOrder();

		// When
		cancel(order, List.of(new OrderCancelLine(APPLE_ITEM_ID, 1)), REFUND_ID);

		// Then
		assertThat(order.getTotalPrice()).isEqualTo(50_000);
	}

	@DisplayName("같은 환불의 내역과 요청 줄이 순서만 다르면 같은 요청으로 본다")
	@Test
	void matchesCancellation_whenOnlyOrderDiffers_returnTrue() {
		// Given
		Order order = paidOrder();
		cancel(order, List.of(
			new OrderCancelLine(APPLE_ITEM_ID, 2),
			new OrderCancelLine(PEAR_ITEM_ID, 1)
		), REFUND_ID);

		// When
		boolean matches = order.matchesCancellation(REFUND_ID, List.of(
			new OrderCancelLine(PEAR_ITEM_ID, 1),
			new OrderCancelLine(APPLE_ITEM_ID, 2)
		));

		// Then
		assertThat(matches).isTrue();
	}

	@DisplayName("금액이 같아도 품목 조합이 다르면 다른 요청으로 본다")
	@Test
	void matchesCancellation_whenItemsDifferAtSameValue_returnFalse() {
		// Given
		Order order = paidOrder();
		cancel(order, List.of(new OrderCancelLine(APPLE_ITEM_ID, 2)), REFUND_ID);

		// When & Then
		assertThat(order.matchesCancellation(REFUND_ID, List.of(new OrderCancelLine(PEAR_ITEM_ID, 1)))).isFalse();
	}

	@DisplayName("품목은 같아도 수량이 다르거나 줄 수가 다르면 다른 요청으로 본다")
	@Test
	void matchesCancellation_whenQuantityOrLineCountDiffers_returnFalse() {
		// Given
		Order order = paidOrder();
		cancel(order, List.of(new OrderCancelLine(APPLE_ITEM_ID, 2)), REFUND_ID);

		// When & Then
		assertThat(order.matchesCancellation(REFUND_ID, List.of(new OrderCancelLine(APPLE_ITEM_ID, 1)))).isFalse();
		assertThat(order.matchesCancellation(REFUND_ID, List.of(
			new OrderCancelLine(APPLE_ITEM_ID, 2),
			new OrderCancelLine(PEAR_ITEM_ID, 1)
		))).isFalse();
	}

	@DisplayName("요청 줄이 비어 있으면 대조하지 않고 같은 요청으로 본다")
	@Test
	void matchesCancellation_whenRequestedLinesEmpty_returnTrue() {
		// Given
		Order order = paidOrder();
		cancel(order, List.of(new OrderCancelLine(APPLE_ITEM_ID, 2)), REFUND_ID);

		// When & Then
		assertThat(order.matchesCancellation(REFUND_ID, List.of())).isTrue();
		assertThat(order.matchesCancellation(REFUND_ID, null)).isTrue();
	}

	@DisplayName("취소 품목 내역이 없는 환불에 품목 목록을 대조하면 다른 요청으로 본다")
	@Test
	void matchesCancellation_whenRefundHasNoRecord_returnFalse() {
		// Given: 취소 품목 내역을 남기기 전에 열린 환불이라 무엇을 취소했는지 알 수 없다
		Order order = paidOrder();

		// When & Then
		assertThat(order.matchesCancellation(REFUND_ID, List.of(new OrderCancelLine(APPLE_ITEM_ID, 1)))).isFalse();
	}

	@DisplayName("다른 환불의 내역은 이 환불의 대조 대상이 아니다")
	@Test
	void matchesCancellation_whenRecordBelongsToAnotherRefund_returnFalse() {
		// Given
		Order order = paidOrder();
		cancel(order, List.of(new OrderCancelLine(APPLE_ITEM_ID, 1)), REFUND_ID);
		cancel(order, List.of(new OrderCancelLine(PEAR_ITEM_ID, 1)), REFUND_ID + 1);

		// When & Then
		assertThat(order.matchesCancellation(REFUND_ID, List.of(new OrderCancelLine(APPLE_ITEM_ID, 1)))).isTrue();
		assertThat(order.matchesCancellation(REFUND_ID, List.of(new OrderCancelLine(PEAR_ITEM_ID, 1)))).isFalse();
	}

	private void cancel(Order order, List<OrderCancelLine> lines, long refundId) {
		order.applyCancellation(order.planCancellation(lines), refundId);
	}

	private Order paidOrder() {
		Order order = Order.create(MEMBER_ID);
		order.addOrderItem(APPLE_PRODUCT_ID, 3, APPLE_UNIT_PRICE);
		order.addOrderItem(PEAR_PRODUCT_ID, 1, PEAR_UNIT_PRICE);
		ReflectionTestUtils.setField(order.getOrderItems().get(0), "id", APPLE_ITEM_ID);
		ReflectionTestUtils.setField(order.getOrderItems().get(1), "id", PEAR_ITEM_ID);
		ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);
		return order;
	}

	private OrderItem appleItem(Order order) {
		return order.getOrderItems().get(0);
	}

	private OrderItem pearItem(Order order) {
		return order.getOrderItems().get(1);
	}
}
