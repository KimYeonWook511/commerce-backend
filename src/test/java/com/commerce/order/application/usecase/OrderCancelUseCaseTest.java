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
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.common.exception.CommonException;
import com.commerce.order.application.dto.OrderCancelRefundStatus;
import com.commerce.order.application.dto.OrderCancelResult;
import com.commerce.order.application.port.OrderIdempotencyStore;
import com.commerce.order.application.service.CancelPaidOrderService;
import com.commerce.order.application.service.CancelPaidOrderService.CancelPaidOrderResult;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.order.infrastructure.OrderIdempotencyStoreUnavailableException;
import com.commerce.payment.application.port.dto.PgCallSource;
import com.commerce.payment.application.usecase.ExecuteRefundUseCase;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundReason;
import com.commerce.payment.domain.RefundRequester;
import com.commerce.payment.domain.RefundStatus;
import com.commerce.payment.domain.exception.DuplicateRefundRequestException;

@ExtendWith(MockitoExtension.class)
class OrderCancelUseCaseTest {

	private static final Long MEMBER_ID = 1L;
	private static final Long ORDER_ID = 100L;
	private static final int APPROVED_AMOUNT = 10_000;
	private static final String IDEMPOTENCY_KEY = "cancel-key-1";

	@Mock
	private OrderIdempotencyStore orderIdempotencyStore;

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

	@DisplayName("결제된 주문을 취소하면 커밋 뒤에 회원 요청 흐름으로 환불을 보낸다")
	@Test
	void cancel_whenPaid_sendsRefundAfterCommit() {
		givenPaidOrderCanceled(RefundStatus.SUCCEEDED);

		OrderCancelResult result = cancelOrderUseCase.cancel(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY, List.of());

		assertThat(result.getRefundStatus()).isEqualTo(OrderCancelRefundStatus.COMPLETED);
		assertThat(result.getRefundedAmount()).isEqualTo(APPROVED_AMOUNT);
		assertThat(result.getRemainingAmount()).isZero();
		then(executeRefundUseCase).should().send(any(), any(), eq(PgCallSource.MEMBER_REQUEST));
	}

	@DisplayName("사람이 이어받아야 하는 환불도 회원에게는 처리 중으로 나간다")
	@Test
	void cancel_whenRefundNeedsManualReview_answersInProgress() {
		givenPaidOrderCanceled(RefundStatus.MANUAL_REVIEW);

		OrderCancelResult result = cancelOrderUseCase.cancel(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY, List.of());

		assertThat(result.getRefundStatus()).isEqualTo(OrderCancelRefundStatus.IN_PROGRESS);
	}

	@DisplayName("결제사를 부르지 못한 채 끝나도 취소는 성공으로 답한다")
	@Test
	void cancel_whenGatewayCallBlowsUp_stillAnswersSuccess() {
		givenReserved();
		given(cancelPaidOrderService.cancelPaidOrder(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY, List.of()))
			.willReturn(canceledResult());
		willThrow(new IllegalStateException("결제사 호출이 깨졌다"))
			.given(executeRefundUseCase).send(any(), any(), any());

		OrderCancelResult result = cancelOrderUseCase.cancel(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY, List.of());

		assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELED);
		assertThat(result.getRefundStatus()).isEqualTo(OrderCancelRefundStatus.IN_PROGRESS);
	}

	@DisplayName("확정이 밀려 되돌려진 뒤에도 회원에게 완료라고 답하지 않는다")
	@Test
	void cancel_whenSettlementRolledBack_doesNotAnswerCompleted() {
		givenReserved();
		CancelPaidOrderResult canceled = canceledResult();
		// 되돌려진 확정이 남긴 자국. 한 요청이 영속성 컨텍스트를 공유하면 확정이 이 인스턴스를 성공으로
		// 바꾸는데, 그 트랜잭션이 되돌려져도 인스턴스의 값은 그대로 남는다.
		ReflectionTestUtils.setField(canceled.refund(), "status", RefundStatus.SUCCEEDED);
		given(cancelPaidOrderService.cancelPaidOrder(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY, List.of()))
			.willReturn(canceled);
		willThrow(new CannotAcquireLockException("결제 행 락을 얻지 못했다"))
			.given(executeRefundUseCase).send(any(), any(), any());

		OrderCancelResult result = cancelOrderUseCase.cancel(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY, List.of());

		assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELED);
		assertThat(result.getRefundStatus()).isEqualTo(OrderCancelRefundStatus.IN_PROGRESS);
	}

	@DisplayName("같은 요청 키의 환불이 이미 있으면 앞 결과를 돌려주고 결제사를 다시 부르지 않는다")
	@Test
	void cancel_whenTransactionReplaysPreviousRefund_doesNotCallGateway() {
		givenReserved();
		given(cancelPaidOrderService.cancelPaidOrder(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY, List.of()))
			.willReturn(replayedResult(OrderStatus.CANCELED));

		OrderCancelResult result = cancelOrderUseCase.cancel(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY, List.of());

		assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELED);
		assertThat(result.getRefundStatus()).isEqualTo(OrderCancelRefundStatus.IN_PROGRESS);
		assertThat(result.getRefundedAmount()).isEqualTo(APPROVED_AMOUNT);
		then(executeRefundUseCase).shouldHaveNoInteractions();
	}

	@DisplayName("주문이 결제완료로 남아 있어도 취소 접수 트랜잭션이 재생을 판정한다")
	@Test
	void cancel_whenOrderStillPaid_stillGoesThroughAcceptanceTransaction() {
		givenReserved();
		given(cancelPaidOrderService.cancelPaidOrder(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY, List.of()))
			.willReturn(replayedResult(OrderStatus.PAID));

		OrderCancelResult result = cancelOrderUseCase.cancel(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY, List.of());

		assertThat(result.getStatus()).isEqualTo(OrderStatus.PAID);
		then(executeRefundUseCase).shouldHaveNoInteractions();
	}

	@DisplayName("취소 가능 판정에 걸린 거부도 흐름을 조립하는 자리를 그대로 지나 회원에게 올라간다")
	@Test
	void cancel_whenCancelPaidOrderServiceRejects_propagatesTheRejection() {
		givenReserved();
		given(cancelPaidOrderService.cancelPaidOrder(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY, List.of()))
			.willThrow(new OrderException(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED));

		assertThatThrownBy(() -> cancelOrderUseCase.cancel(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY, List.of()))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED));
	}

	@DisplayName("멱등키가 없으면 요청 형식 검증으로 거절한다")
	@Test
	void cancel_whenIdempotencyKeyMissing_throws() {
		assertThatThrownBy(() -> cancelOrderUseCase.cancel(MEMBER_ID, ORDER_ID, " ", List.of()))
			.isInstanceOf(CommonException.class);
		then(orderIdempotencyStore).shouldHaveNoInteractions();
	}

	@DisplayName("같은 요청을 선점하지 못하면 처리 중이라는 응답으로 거절한다")
	@Test
	void cancel_whenPreemptionLost_throwsInProgress() {
		given(orderIdempotencyStore.reserveCancel(eq(ORDER_ID), eq(IDEMPOTENCY_KEY), any())).willReturn(false);

		assertThatThrownBy(() -> cancelOrderUseCase.cancel(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY, List.of()))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_CANCEL_IN_PROGRESS));
		then(orderIdempotencyStore).should(never()).clearCancel(anyLong(), anyString());
	}

	@DisplayName("유일 제약에 막힌 취소도 서버 오류가 아니라 처리 중이라는 응답으로 나간다")
	@Test
	void cancel_whenUniqueConstraintHits_throwsInProgress() {
		givenReserved();
		given(cancelPaidOrderService.cancelPaidOrder(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY, List.of()))
			.willThrow(new DuplicateRefundRequestException());

		assertThatThrownBy(() -> cancelOrderUseCase.cancel(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY, List.of()))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_CANCEL_IN_PROGRESS));
	}

	@DisplayName("중복 키가 아닌 무결성 위반은 처리 중으로 바뀌지 않고 그대로 올라간다")
	@Test
	void cancel_whenOtherIntegrityViolation_doesNotHideIt() {
		givenReserved();
		// 유일 제약이 아닌 위반은 adapter가 번역하지 않고 그대로 올려 보낸다. 여기서 "처리 중"으로
		// 바꾸면 실제 정합성 장애가 안전망에 닿지 못한다.
		given(cancelPaidOrderService.cancelPaidOrder(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY, List.of()))
			.willThrow(new DataIntegrityViolationException("Column 'refund_key' cannot be null"));

		assertThatThrownBy(() -> cancelOrderUseCase.cancel(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY, List.of()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@DisplayName("선점 저장소가 죽으면 유일 제약 경로로 물러나고 선점을 해제하지 않는다")
	@Test
	void cancel_whenPreemptionStoreUnavailable_fallsBackWithoutClearing() {
		given(orderIdempotencyStore.reserveCancel(eq(ORDER_ID), eq(IDEMPOTENCY_KEY), any()))
			.willThrow(new OrderIdempotencyStoreUnavailableException(new RuntimeException("redis down")));
		given(cancelPaidOrderService.cancelPaidOrder(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY, List.of()))
			.willReturn(canceledResult());
		given(executeRefundUseCase.send(any(), any(), any())).willReturn(RefundStatus.SUCCEEDED);

		OrderCancelResult result = cancelOrderUseCase.cancel(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY, List.of());

		assertThat(result.getRefundStatus()).isEqualTo(OrderCancelRefundStatus.COMPLETED);
		then(orderIdempotencyStore).should(never()).clearCancel(anyLong(), anyString());
	}

	// ── 헬퍼 ──

	private void givenReserved() {
		given(orderIdempotencyStore.reserveCancel(eq(ORDER_ID), eq(IDEMPOTENCY_KEY), any())).willReturn(true);
	}

	private void givenPaidOrderCanceled(RefundStatus refundStatus) {
		givenReserved();
		given(cancelPaidOrderService.cancelPaidOrder(MEMBER_ID, ORDER_ID, IDEMPOTENCY_KEY, List.of()))
			.willReturn(canceledResult());
		given(executeRefundUseCase.send(any(), any(), any())).willReturn(refundStatus);
	}

	/** 트랜잭션이 커밋해 돌려주는 것은 이미 취소된 주문이다 */
	private CancelPaidOrderResult canceledResult() {
		Payment payment = Payment.start(
			ORDER_ID, MEMBER_ID, PaymentPg.NAVERPAY, "PK-1", "idem-1", APPROVED_AMOUNT);
		payment.markInProgress("pg-payment-1", LocalDateTime.now());
		payment.succeed(APPROVED_AMOUNT, "pg-tx-1");
		ReflectionTestUtils.setField(payment, "id", 7L);

		Refund refund = Refund.open(7L, "RF-1", RefundRequester.MEMBER, IDEMPOTENCY_KEY,
			APPROVED_AMOUNT, RefundReason.ORDER_CANCELED);
		ReflectionTestUtils.setField(refund, "id", 9L);

		return CancelPaidOrderResult.accepted(order(OrderStatus.CANCELED), payment, refund, 0);
	}

	/** 같은 요청 키의 환불이 이미 있어 트랜잭션이 앞 결과를 그대로 돌려준 경우 */
	private CancelPaidOrderResult replayedResult(OrderStatus orderStatus) {
		CancelPaidOrderResult accepted = canceledResult();
		return CancelPaidOrderResult.replayed(
			order(orderStatus), accepted.payment(), accepted.refund(), accepted.remainingAmount());
	}

	private Order order(OrderStatus status) {
		Order order = Order.create(MEMBER_ID);
		ReflectionTestUtils.setField(order, "id", ORDER_ID);
		ReflectionTestUtils.setField(order, "status", status);
		return order;
	}
}
