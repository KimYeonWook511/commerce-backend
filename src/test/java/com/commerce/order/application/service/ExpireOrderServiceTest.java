package com.commerce.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.outbox.stock.application.dto.StockRestoreOutboxCreateCommand;
import com.commerce.outbox.stock.application.service.StockRestoreOutboxCreateService;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentCloseCode;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.repository.PaymentRepository;

@ExtendWith(MockitoExtension.class)
class ExpireOrderServiceTest {

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private StockRestoreOutboxCreateService stockRestoreOutboxCreateService;

	@InjectMocks
	private ExpireOrderService expireOrderService;

	@DisplayName("결제가 걸리지 않은 만료 주문을 취소하고 재고 복구 outbox 이벤트를 저장한다")
	@Test
	void expireOrder_whenExpired_cancelOrderAndSaveOutbox() {
		// given
		Order order = createOrderWithItem();
		LocalDateTime requestedAt = LocalDateTime.of(2026, 5, 6, 10, 0);

		given(orderRepository.findByIdWithItems(order.getId())).willReturn(Optional.of(order));

		// when
		expireOrderService.expireOrder(order.getId(), requestedAt);

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
		then(paymentRepository).should(never()).save(any(Payment.class));
		then(stockRestoreOutboxCreateService).should()
			.createOutboxEvent(org.mockito.ArgumentMatchers.argThat(command ->
				command.getOrderId().equals(order.getId())
					&& command.getItems().size() == 1
					&& command.getItems().getFirst().getProductId().equals(1L)
					&& command.getItems().getFirst().getQuantity() == 2
					&& command.getRequestedAt().equals(requestedAt)
			));
	}

	@DisplayName("승인을 아직 부르지 않은 결제가 걸려 있으면 그 결제를 주문 만료로 종결하고 주문을 취소한다")
	@Test
	void expireOrder_whenPaymentWaitsForApprovalCall_closesPaymentAndCancelsOrder() {
		// given
		Order order = createOrderWithItem();
		Payment payment = startedPayment(order);
		LocalDateTime requestedAt = LocalDateTime.of(2026, 5, 6, 10, 0);

		given(orderRepository.findByIdWithItems(order.getId())).willReturn(Optional.of(order));
		given(paymentRepository.findActiveByOrderId(order.getId())).willReturn(Optional.of(payment));

		// when
		expireOrderService.expireOrder(order.getId(), requestedAt);

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
		assertThat(payment.getCloseCode()).isEqualTo(PaymentCloseCode.ORDER_EXPIRED);
		assertThat(payment.getActiveOrderKey()).isNull();
		then(paymentRepository).should().save(payment);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
		then(stockRestoreOutboxCreateService).should().createOutboxEvent(any(StockRestoreOutboxCreateCommand.class));
	}

	@DisplayName("승인 호출 중인 결제가 걸려 있으면 주문을 만료하지 않고 결제도 그대로 둔다")
	@Test
	void expireOrder_whenPaymentApprovalInProgress_leavesOrderAndPayment() {
		// given
		Order order = createOrderWithItem();
		Payment payment = startedPayment(order);
		payment.markInProgress("pg-payment-1", LocalDateTime.of(2026, 5, 6, 9, 0));
		LocalDateTime requestedAt = LocalDateTime.of(2026, 5, 6, 10, 0);

		given(orderRepository.findByIdWithItems(order.getId())).willReturn(Optional.of(order));
		given(paymentRepository.findActiveByOrderId(order.getId())).willReturn(Optional.of(payment));

		// when
		expireOrderService.expireOrder(order.getId(), requestedAt);

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.INIT);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
		then(paymentRepository).should(never()).save(any(Payment.class));
		then(stockRestoreOutboxCreateService).should(never())
			.createOutboxEvent(any(StockRestoreOutboxCreateCommand.class));
	}

	@DisplayName("승인이 성공한 결제가 걸린 결제완료 주문은 오류 없이 멈추고 아무것도 바꾸지 않는다")
	@Test
	void expireOrder_whenPaymentSucceeded_stopsWithoutError() {
		// given
		Order order = createOrderWithItem();
		ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);
		Payment payment = startedPayment(order);
		payment.markInProgress("pg-payment-1", LocalDateTime.of(2026, 5, 6, 9, 0));
		payment.succeed(payment.getAmount(), "pg-tx-1");
		LocalDateTime requestedAt = LocalDateTime.of(2026, 5, 6, 10, 0);

		given(orderRepository.findByIdWithItems(order.getId())).willReturn(Optional.of(order));
		given(paymentRepository.findActiveByOrderId(order.getId())).willReturn(Optional.of(payment));

		// when
		expireOrderService.expireOrder(order.getId(), requestedAt);

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		then(paymentRepository).should(never()).save(any(Payment.class));
		then(stockRestoreOutboxCreateService).should(never())
			.createOutboxEvent(any(StockRestoreOutboxCreateCommand.class));
	}

	@DisplayName("주문이 존재하지 않으면 만료 처리에 실패한다")
	@Test
	void expireOrder_whenOrderNotFound_throwException() {
		// given
		LocalDateTime requestedAt = LocalDateTime.of(2026, 5, 6, 10, 0);
		given(orderRepository.findByIdWithItems(100L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> expireOrderService.expireOrder(100L, requestedAt))
			.isInstanceOf(OrderException.class)
			.extracting("errorCode")
			.isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);
	}

	@DisplayName("주문이 이미 취소 상태면 만료 처리 시 예외가 발생한다")
	@Test
	void expireOrder_whenOrderAlreadyCanceled_throwException() {
		// given
		Order order = createOrderWithItem();
		LocalDateTime requestedAt = LocalDateTime.of(2026, 5, 6, 10, 0);
		ReflectionTestUtils.setField(order, "status", OrderStatus.CANCELED);

		given(orderRepository.findByIdWithItems(order.getId())).willReturn(Optional.of(order));

		// when & then
		assertThatThrownBy(() -> expireOrderService.expireOrder(order.getId(), requestedAt))
			.isInstanceOf(OrderException.class)
			.extracting("errorCode")
			.isEqualTo(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED);
		then(stockRestoreOutboxCreateService).should(never())
			.createOutboxEvent(org.mockito.ArgumentMatchers.any(StockRestoreOutboxCreateCommand.class));
	}

	private Payment startedPayment(Order order) {
		return Payment.start(order.getId(), order.getMemberId(), PaymentPg.NAVERPAY,
			"PK-expire", "IDEM-expire", order.getTotalPrice());
	}

	private Order createOrderWithItem() {
		Order order = Order.create(10L);
		ReflectionTestUtils.setField(order, "id", 100L);
		order.addOrderItem(1L, 2, 1000);
		return order;
	}
}
