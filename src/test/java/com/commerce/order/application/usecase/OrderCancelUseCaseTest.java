package com.commerce.order.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

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
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundReason;
import com.commerce.payment.domain.RefundRequester;
import com.commerce.payment.domain.RefundStatus;

@ExtendWith(MockitoExtension.class)
class OrderCancelUseCaseTest {

	private static final Long MEMBER_ID = 1L;
	private static final Long ORDER_ID = 100L;
	private static final int APPROVED_AMOUNT = 10_000;
	private static final String IDEMPOTENCY_KEY = "cancel-key-1";

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private OrderIdempotencyStore orderIdempotencyStore;

	@Mock
	private CancelOrderService cancelOrderService;

	@Mock
	private CancelPaidOrderService cancelPaidOrderService;

	@Mock
	private ExecuteRefundUseCase executeRefundUseCase;

	@InjectMocks
	private CancelOrderUseCase cancelOrderUseCase;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(cancelOrderUseCase, "idempotencyTtlSeconds", 60L);
	}

	@DisplayName("결제 전 주문 취소는 재고만 돌려주는 경로로 간다")
	@Test
	void cancel_whenNotPaid_delegatesToPlainCancel() {
		Order order = order(OrderStatus.INIT);
		OrderCancelResult expected = OrderCancelResult.from(order);
		givenReserved();
		given(orderRepository.findByIdAndMemberId(ORDER_ID, MEMBER_ID)).willReturn(Optional.of(order));
		given(cancelOrderService.cancelOrder(MEMBER_ID, ORDER_ID)).willReturn(expected);

		OrderCancelResult result = cancelOrderUseCase.cancel(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY);

		assertThat(result).isSameAs(expected);
		then(cancelPaidOrderService).shouldHaveNoInteractions();
	}

	@DisplayName("결제된 주문을 취소하면 커밋 뒤에 회원 요청 흐름으로 환불을 보낸다")
	@Test
	void cancel_whenPaid_sendsRefundAfterCommit() {
		givenPaidOrderCanceled(RefundStatus.SUCCEEDED);

		OrderCancelResult result = cancelOrderUseCase.cancel(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY);

		assertThat(result.getRefundStatus()).isEqualTo(OrderCancelRefundStatus.COMPLETED);
		assertThat(result.getRefundedAmount()).isEqualTo(APPROVED_AMOUNT);
		assertThat(result.getRemainingAmount()).isZero();
		then(executeRefundUseCase).should().send(any(), any(), eq(PgCallSource.MEMBER_REQUEST));
	}

	@DisplayName("사람이 이어받아야 하는 환불도 회원에게는 처리 중으로 나간다")
	@Test
	void cancel_whenRefundNeedsManualReview_answersInProgress() {
		givenPaidOrderCanceled(RefundStatus.MANUAL_REVIEW);

		OrderCancelResult result = cancelOrderUseCase.cancel(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY);

		assertThat(result.getRefundStatus()).isEqualTo(OrderCancelRefundStatus.IN_PROGRESS);
	}

	@DisplayName("결제사를 부르지 못한 채 끝나도 취소는 성공으로 답한다")
	@Test
	void cancel_whenGatewayCallBlowsUp_stillAnswersSuccess() {
		givenReserved();
		Order order = order(OrderStatus.PAID);
		given(orderRepository.findByIdAndMemberId(ORDER_ID, MEMBER_ID)).willReturn(Optional.of(order));
		given(cancelPaidOrderService.cancelPaidOrder(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY))
			.willReturn(canceledResult());
		willThrow(new IllegalStateException("결제사 호출이 깨졌다"))
			.given(executeRefundUseCase).send(any(), any(), any());

		OrderCancelResult result = cancelOrderUseCase.cancel(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY);

		assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELED);
		assertThat(result.getRefundStatus()).isEqualTo(OrderCancelRefundStatus.IN_PROGRESS);
	}

	@DisplayName("멱등키가 없으면 요청 형식 검증으로 거절한다")
	@Test
	void cancel_whenIdempotencyKeyMissing_throws() {
		assertThatThrownBy(() -> cancelOrderUseCase.cancel(MEMBER_ID, ORDER_ID, " "))
			.isInstanceOf(CommonException.class);
		then(orderIdempotencyStore).shouldHaveNoInteractions();
	}

	@DisplayName("같은 요청을 선점하지 못하면 처리 중이라는 응답으로 거절한다")
	@Test
	void cancel_whenPreemptionLost_throwsInProgress() {
		given(orderIdempotencyStore.reserveCancel(eq(ORDER_ID), eq(IDEMPOTENCY_KEY), any())).willReturn(false);

		assertThatThrownBy(() -> cancelOrderUseCase.cancel(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_CANCEL_IN_PROGRESS));
		then(orderIdempotencyStore).should(never()).clearCancel(anyLong(), anyString());
	}

	@DisplayName("유일 제약에 막힌 취소도 서버 오류가 아니라 처리 중이라는 응답으로 나간다")
	@Test
	void cancel_whenUniqueConstraintHits_throwsInProgress() {
		givenReserved();
		Order order = order(OrderStatus.PAID);
		given(orderRepository.findByIdAndMemberId(ORDER_ID, MEMBER_ID)).willReturn(Optional.of(order));
		given(cancelPaidOrderService.cancelPaidOrder(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY))
			.willThrow(new DuplicateKeyException("uk_refund_payment_idempotency"));

		assertThatThrownBy(() -> cancelOrderUseCase.cancel(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_CANCEL_IN_PROGRESS));
	}

	@DisplayName("중복 키가 아닌 무결성 위반은 처리 중으로 바뀌지 않고 그대로 올라간다")
	@Test
	void cancel_whenOtherIntegrityViolation_doesNotHideIt() {
		givenReserved();
		Order order = order(OrderStatus.PAID);
		given(orderRepository.findByIdAndMemberId(ORDER_ID, MEMBER_ID)).willReturn(Optional.of(order));
		// 이 트랜잭션은 주문·환불·재고·결제를 함께 저장해 NOT NULL·외래 키 위반도 같은 상위 예외로 온다.
		// 그것까지 "처리 중"으로 바꾸면 실제 정합성 장애가 안전망에 닿지 못한다.
		given(cancelPaidOrderService.cancelPaidOrder(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY))
			.willThrow(new DataIntegrityViolationException("Column 'refund_key' cannot be null"));

		assertThatThrownBy(() -> cancelOrderUseCase.cancel(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@DisplayName("선점 저장소가 죽으면 유일 제약 경로로 물러나고 선점을 해제하지 않는다")
	@Test
	void cancel_whenPreemptionStoreUnavailable_fallsBackWithoutClearing() {
		given(orderIdempotencyStore.reserveCancel(eq(ORDER_ID), eq(IDEMPOTENCY_KEY), any()))
			.willThrow(new OrderIdempotencyStoreUnavailableException(new RuntimeException("redis down")));
		Order order = order(OrderStatus.INIT);
		given(orderRepository.findByIdAndMemberId(ORDER_ID, MEMBER_ID)).willReturn(Optional.of(order));
		given(cancelOrderService.cancelOrder(MEMBER_ID, ORDER_ID)).willReturn(OrderCancelResult.from(order));

		OrderCancelResult result = cancelOrderUseCase.cancel(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY);

		assertThat(result.getRefundStatus()).isEqualTo(OrderCancelRefundStatus.NONE);
		then(orderIdempotencyStore).should(never()).clearCancel(anyLong(), anyString());
	}

	@DisplayName("남의 주문 번호로는 취소할 수 없고 그 주문이 있는지도 드러나지 않는다")
	@Test
	void cancel_whenOrderNotFound_throwsNotFound() {
		givenReserved();
		given(orderRepository.findByIdAndMemberId(ORDER_ID, MEMBER_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> cancelOrderUseCase.cancel(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));
		then(orderIdempotencyStore).should().clearCancel(ORDER_ID, IDEMPOTENCY_KEY);
	}

	// ── 헬퍼 ──

	private void givenReserved() {
		given(orderIdempotencyStore.reserveCancel(eq(ORDER_ID), eq(IDEMPOTENCY_KEY), any())).willReturn(true);
	}

	private void givenPaidOrderCanceled(RefundStatus refundStatus) {
		givenReserved();
		Order order = order(OrderStatus.PAID);
		given(orderRepository.findByIdAndMemberId(ORDER_ID, MEMBER_ID)).willReturn(Optional.of(order));
		given(cancelPaidOrderService.cancelPaidOrder(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY))
			.willReturn(canceledResult());
		given(executeRefundUseCase.send(any(), any(), any())).willReturn(refundStatus);
	}

	/** 트랜잭션이 커밋해 돌려주는 것은 이미 취소된 주문이라, 경로를 고르는 조회와 다른 인스턴스로 둔다 */
	private CancelPaidOrderResult canceledResult() {
		Payment payment = Payment.start(
			ORDER_ID, MEMBER_ID, PaymentPg.NAVERPAY, "PK-1", "idem-1", APPROVED_AMOUNT);
		payment.markInProgress("pg-payment-1", LocalDateTime.now());
		payment.succeed(APPROVED_AMOUNT, "pg-tx-1");
		ReflectionTestUtils.setField(payment, "id", 7L);

		Refund refund = Refund.open(7L, "RF-1", RefundRequester.MEMBER, IDEMPOTENCY_KEY,
			APPROVED_AMOUNT, RefundReason.ORDER_CANCELED);
		ReflectionTestUtils.setField(refund, "id", 9L);

		return new CancelPaidOrderResult(order(OrderStatus.CANCELED), payment, refund, 0);
	}

	private Order order(OrderStatus status) {
		Order order = Order.create(MEMBER_ID);
		ReflectionTestUtils.setField(order, "id", ORDER_ID);
		ReflectionTestUtils.setField(order, "status", status);
		return order;
	}
}
