package com.commerce.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
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

@ExtendWith(MockitoExtension.class)
class OrderExpirationServiceTest {

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private StockRestoreOutboxCreateService stockRestoreOutboxCreateService;

	@InjectMocks
	private OrderExpirationService orderExpirationService;

	@DisplayName("만료 주문을 취소하고 재고 복구 outbox 이벤트를 저장한다")
	@Test
	void expireOrder_whenExpired_cancelOrderAndSaveOutbox() {
		// given
		Order order = createOrderWithItem();
		LocalDateTime requestedAt = LocalDateTime.of(2026, 5, 6, 10, 0);

		given(orderRepository.findByIdWithItems(order.getId())).willReturn(Optional.of(order));

		// when
		orderExpirationService.expireOrder(order.getId(), requestedAt);

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
		then(stockRestoreOutboxCreateService).should()
			.createOutboxEvent(org.mockito.ArgumentMatchers.argThat(command ->
				command.getOrderId().equals(order.getId())
					&& command.getItems().size() == 1
					&& command.getItems().getFirst().getProductId().equals(1L)
					&& command.getItems().getFirst().getQuantity() == 2
					&& command.getRequestedAt().equals(requestedAt)
			));
	}

	@DisplayName("주문이 존재하지 않으면 만료 처리에 실패한다")
	@Test
	void expireOrder_whenOrderNotFound_throwException() {
		// given
		LocalDateTime requestedAt = LocalDateTime.of(2026, 5, 6, 10, 0);
		given(orderRepository.findByIdWithItems(100L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> orderExpirationService.expireOrder(100L, requestedAt))
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
		assertThatThrownBy(() -> orderExpirationService.expireOrder(order.getId(), requestedAt))
			.isInstanceOf(OrderException.class)
			.extracting("errorCode")
			.isEqualTo(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED);
		then(stockRestoreOutboxCreateService).should(never())
			.createOutboxEvent(org.mockito.ArgumentMatchers.any(StockRestoreOutboxCreateCommand.class));
	}

	private Order createOrderWithItem() {
		Order order = Order.create(10L);
		ReflectionTestUtils.setField(order, "id", 100L);
		order.addOrderItem(1L, 2, 1000);
		return order;
	}
}
