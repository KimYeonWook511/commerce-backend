package com.commerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.member.domain.Member;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.order.repository.OrderRepository;
import com.commerce.product.domain.Product;
import com.commerce.stock.service.StockService;

@ExtendWith(MockitoExtension.class)
class OrderServiceExpirationTest {

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private StockService stockService;

	@InjectMocks
	private OrderService orderService;

	@DisplayName("만료 주문을 취소하고 재고를 복구한다")
	@Test
	void expireOrder_whenExpired_cancelOrderAndRestoreStock() {
		// given
		Order order = createOrderWithItem();

		given(orderRepository.findByIdWithItems(order.getId())).willReturn(Optional.of(order));

		// when
		orderService.expireOrder(order.getId());

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
		then(stockService).should()
			.increaseWithPessimisticLock(eq(1L), eq(2));
	}

	@DisplayName("주문이 존재하지 않으면 만료 처리에 실패한다")
	@Test
	void expireOrder_whenOrderNotFound_throwException() {
		// given
		given(orderRepository.findByIdWithItems(100L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> orderService.expireOrder(100L))
			.isInstanceOf(OrderException.class)
			.extracting("errorCode")
			.isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);
	}

	@DisplayName("주문이 이미 취소 상태면 만료 처리 시 예외가 발생한다")
	@Test
	void expireOrder_whenOrderAlreadyCanceled_throwException() {
		// given
		Order order = createOrderWithItem();
		ReflectionTestUtils.setField(order, "status", OrderStatus.CANCELED);

		given(orderRepository.findByIdWithItems(order.getId())).willReturn(Optional.of(order));

		// when & then
		assertThatThrownBy(() -> orderService.expireOrder(order.getId()))
			.isInstanceOf(OrderException.class)
			.extracting("errorCode")
			.isEqualTo(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED);
		then(stockService).should(never()).increaseWithPessimisticLock(eq(1L), eq(2));
	}

	private Order createOrderWithItem() {
		Member member = Member.builder()
			.email("expire@example.com")
			.password("password123")
			.username("expire-user")
			.build();
		ReflectionTestUtils.setField(member, "id", 10L);

		Product product = Product.builder()
			.name("expire-product")
			.price(1000)
			.build();
		ReflectionTestUtils.setField(product, "id", 1L);

		Order order = Order.create(member);
		ReflectionTestUtils.setField(order, "id", 100L);
		order.addOrderItem(product, 2);
		return order;
	}
}
