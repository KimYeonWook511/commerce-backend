package com.commerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.member.domain.Member;
import com.commerce.member.exception.MemberErrorCode;
import com.commerce.member.exception.MemberException;
import com.commerce.member.repository.MemberRepository;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.order.repository.OrderRepository;
import com.commerce.order.redis.OrderIdempotencyStore;
import com.commerce.order.service.command.OrderCreateItem;
import com.commerce.order.service.command.OrderCreateCommand;
import com.commerce.order.service.result.OrderCancelResult;
import com.commerce.order.service.result.OrderCreateResult;
import com.commerce.product.domain.Product;
import com.commerce.product.exception.ProductErrorCode;
import com.commerce.product.exception.ProductException;
import com.commerce.product.repository.ProductRepository;
import com.commerce.stock.exception.StockErrorCode;
import com.commerce.stock.exception.StockException;
import com.commerce.stock.service.StockService;
import com.commerce.stock.service.command.StockDecreaseBatchCommand;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

	@Mock
	private MemberRepository memberRepository;

	@Mock
	private ProductRepository productRepository;

	@Mock
	private StockService stockService;

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private OrderIdempotencyStore orderIdempotencyStore;

	@InjectMocks
	private OrderService orderService;

	private final String idempotencyKey = "idempotency-key";

	@DisplayName("기본 주문 생성은 비관적 락 방식으로 재고를 차감한다")
	@Test
	void createOrder_whenValidRequest_usePessimisticLockDecrease() {
		// given
		Member member = createMember(1L);
		Product product1 = createProduct(10L, "product-1", 1000);
		Product product2 = createProduct(11L, "product-2", 2000);
		OrderCreateCommand command = createDefaultRequest();
		stubForSuccess(member, product1, product2);
		stubForIdempotencyReserved();

		// when
		OrderCreateResult result = orderService.createOrder(command);

		// then
		then(stockService).should().decreaseWithPessimisticLock(10L, 2);
		then(stockService).should().decreaseWithPessimisticLock(11L, 1);

		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		then(orderRepository).should().save(orderCaptor.capture());

		Order savedOrder = orderCaptor.getValue();
		assertThat(savedOrder.getTotalPrice()).isEqualTo(4000);
		assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.INIT);
		assertThat(result.getOrderId()).isEqualTo(100L);
		assertThat(result.getTotalPrice()).isEqualTo(4000);
		assertThat(result.getStatus()).isEqualTo(OrderStatus.INIT);
	}

	@DisplayName("락 없이 주문을 생성하면 기본 차감 로직을 사용한다")
	@Test
	void createOrderWithoutLock_whenValidRequest_useDefaultDecrease() {
		// given
		Member member = createMember(1L);
		Product product1 = createProduct(10L, "product-1", 1000);
		Product product2 = createProduct(11L, "product-2", 2000);
		OrderCreateCommand command = createDefaultRequest();
		stubForSuccess(member, product1, product2);

		// when
		orderService.createOrderWithoutLock(command);

		// then
		then(stockService).should().decrease(10L, 2);
		then(stockService).should().decrease(11L, 1);
	}

	@DisplayName("주문 취소를 요청하면 재고가 복구된다")
	@Test
	void cancelOrder_whenInitStatus_restoreStock() {
		// given
		Member member = createMember(1L);
		Product product = createProduct(10L, "product-1", 1000);
		Order order = Order.create(member);
		order.addOrderItem(product, 2);
		ReflectionTestUtils.setField(order, "id", 100L);

		given(orderRepository.findByIdAndMemberIdWithItems(100L, 1L)).willReturn(Optional.of(order));

		// when
		OrderCancelResult result = orderService.cancelOrder(1L, 100L);

		// then
		then(stockService).should().increaseWithPessimisticLock(10L, 2);
		assertThat(result.getOrderId()).isEqualTo(100L);
		assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELED);
	}

	@DisplayName("주문 취소 시 재고 복구는 상품 ID 순서로 호출된다")
	@Test
	void cancelOrder_whenMultipleItems_sortByProductId() {
		// given
		Member member = createMember(1L);
		Product product1 = createProduct(5L, "product-5", 1000);
		Product product2 = createProduct(2L, "product-2", 1000);
		Order order = Order.create(member);
		order.addOrderItem(product1, 1);
		order.addOrderItem(product2, 1);
		ReflectionTestUtils.setField(order, "id", 100L);

		given(orderRepository.findByIdAndMemberIdWithItems(100L, 1L)).willReturn(Optional.of(order));

		// when
		orderService.cancelOrder(1L, 100L);

		// then
		InOrder inOrder = org.mockito.Mockito.inOrder(stockService);
		inOrder.verify(stockService).increaseWithPessimisticLock(2L, 1);
		inOrder.verify(stockService).increaseWithPessimisticLock(5L, 1);
	}

	@DisplayName("주문 상태가 초기 상태가 아니면 취소에 실패한다")
	@Test
	void cancelOrder_whenStatusNotInit_throwException() {
		// given
		Member member = createMember(1L);
		Product product = createProduct(10L, "product-1", 1000);
		Order order = Order.create(member);
		order.addOrderItem(product, 1);
		ReflectionTestUtils.setField(order, "id", 100L);
		ReflectionTestUtils.setField(order, "status", OrderStatus.RECEIVED);

		given(orderRepository.findByIdAndMemberIdWithItems(100L, 1L)).willReturn(Optional.of(order));

		// when & then
		assertThatThrownBy(() -> orderService.cancelOrder(1L, 100L))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException) exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED);
			});
	}

	@DisplayName("다른 회원의 주문은 취소할 수 없다")
	@Test
	void cancelOrder_whenMemberMismatch_throwException() {
		// given
		Member member = createMember(2L);
		Product product = createProduct(10L, "product-1", 1000);
		Order order = Order.create(member);
		order.addOrderItem(product, 1);
		ReflectionTestUtils.setField(order, "id", 100L);

		given(orderRepository.findByIdAndMemberIdWithItems(100L, 1L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> orderService.cancelOrder(1L, 100L))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException) exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);
			});
	}

	@DisplayName("동기화 차감 방식을 사용해서 주문을 생성한다")
	@Test
	void createOrderWithSynchronized_whenValidRequest_useSynchronizedDecrease() {
		// given
		Member member = createMember(1L);
		Product product1 = createProduct(10L, "product-1", 1000);
		Product product2 = createProduct(11L, "product-2", 2000);
		OrderCreateCommand command = createDefaultRequest();
		stubForSuccess(member, product1, product2);

		// when
		orderService.createOrderWithSynchronized(command);

		// then
		then(stockService).should().decreaseWithSynchronized(10L, 2);
		then(stockService).should().decreaseWithSynchronized(11L, 1);
	}

	@DisplayName("동기화+트랜잭션 차감 방식을 사용해서 주문을 생성한다")
	@Test
	void createOrderWithSynchronizedAndTransaction_whenValidRequest_useSynchronizedTransactionDecrease() {
		// given
		Member member = createMember(1L);
		Product product1 = createProduct(10L, "product-1", 1000);
		Product product2 = createProduct(11L, "product-2", 2000);
		OrderCreateCommand command = createDefaultRequest();
		stubForSuccess(member, product1, product2);

		// when
		orderService.createOrderWithSynchronizedAndTransaction(command);

		// then
		then(stockService).should().decreaseWithSynchronizedAndTransaction(10L, 2);
		then(stockService).should().decreaseWithSynchronizedAndTransaction(11L, 1);
	}

	@DisplayName("ReentrantLock+트랜잭션 차감 방식을 사용해서 주문을 생성한다")
	@Test
	void createOrderWithReentrantLockAndTransaction_whenValidRequest_useReentrantLockDecrease() {
		// given
		Member member = createMember(1L);
		Product product1 = createProduct(10L, "product-1", 1000);
		Product product2 = createProduct(11L, "product-2", 2000);
		OrderCreateCommand command = createDefaultRequest();
		stubForSuccess(member, product1, product2);

		// when
		orderService.createOrderWithReentrantLockAndTransaction(command);

		// then
		then(stockService).should().decreaseWithReentrantLockAndTransaction(10L, 2);
		then(stockService).should().decreaseWithReentrantLockAndTransaction(11L, 1);
	}

	@DisplayName("낙관적 락 차감 방식을 사용해서 주문을 생성한다")
	@Test
	void createOrderWithOptimisticLock_whenValidRequest_useOptimisticLockDecrease() {
		// given
		Member member = createMember(1L);
		Product product1 = createProduct(10L, "product-1", 1000);
		Product product2 = createProduct(11L, "product-2", 2000);
		OrderCreateCommand command = createDefaultRequest();
		stubForSuccess(member, product1, product2);

		// when
		orderService.createOrderWithOptimisticLock(command);

		// then
		then(stockService).should().decreaseWithOptimisticLock(10L, 2);
		then(stockService).should().decreaseWithOptimisticLock(11L, 1);
	}

	@DisplayName("비관적 락 차감 방식을 사용해서 주문을 생성한다")
	@Test
	void createOrderWithPessimisticLock_whenValidRequest_usePessimisticLockDecrease() {
		// given
		Member member = createMember(1L);
		Product product1 = createProduct(10L, "product-1", 1000);
		Product product2 = createProduct(11L, "product-2", 2000);
		OrderCreateCommand command = createDefaultRequest();
		stubForSuccess(member, product1, product2);

		// when
		orderService.createOrderWithPessimisticLock(command);

		// then
		then(stockService).should().decreaseWithPessimisticLock(10L, 2);
		then(stockService).should().decreaseWithPessimisticLock(11L, 1);
	}

	@DisplayName("비관적 락 차감 방식(정렬)을 사용해서 주문을 생성한다")
	@Test
	void createOrderWithPessimisticLockOrdered_whenValidRequest_usePessimisticLockDecrease() {
		// given
		Member member = createMember(1L);
		Product product1 = createProduct(10L, "product-1", 1000);
		Product product2 = createProduct(11L, "product-2", 2000);
		OrderCreateCommand command = createDefaultRequest();
		stubForSuccess(member, product1, product2);

		// when
		orderService.createOrderWithPessimisticLockOrdered(command);

		// then
		then(stockService).should().decreaseWithPessimisticLock(10L, 2);
		then(stockService).should().decreaseWithPessimisticLock(11L, 1);
	}

	@DisplayName("비관적 락 차감 방식(배치)을 사용해서 주문을 생성한다")
	@Test
	void createOrderWithPessimisticLockBatch_whenValidRequest_usePessimisticLockDecrease() {
		// given
		Member member = createMember(1L);
		Product product1 = createProduct(10L, "product-1", 1000);
		Product product2 = createProduct(11L, "product-2", 2000);
		OrderCreateCommand command = createDefaultRequest();
		stubForSuccessBatch(member, product1, product2);

		// when
		orderService.createOrderWithPessimisticLockBatch(command);

		// then
		ArgumentCaptor<StockDecreaseBatchCommand> requestCaptor =
			ArgumentCaptor.forClass(StockDecreaseBatchCommand.class);
		then(stockService).should().decreaseBatchWithPessimisticLock(requestCaptor.capture());
		assertThat(requestCaptor.getValue().getQuantitiesByProductId())
			.containsEntry(10L, 2)
			.containsEntry(11L, 1);
	}

	@DisplayName("회원이 없으면 주문 생성에 실패한다")
	@Test
	void createOrder_whenMemberNotFound_throwException() {
		// given
		OrderCreateCommand command = OrderCreateCommand.builder()
			.memberId(1L)
			.idempotencyKey(idempotencyKey)
			.items(List.of(OrderCreateItem.builder().productId(10L).quantity(1).build()))
			.build();

		stubForIdempotencyReserved();
		given(memberRepository.findById(1L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> orderService.createOrder(command))
			.isInstanceOf(MemberException.class)
			.satisfies(exception -> {
				MemberException memberException = (MemberException) exception;
				assertThat(memberException.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
			});
	}

	@DisplayName("상품이 없으면 주문 생성에 실패한다")
	@Test
	void createOrder_whenProductNotFound_throwException() {
		// given
		Member member = createMember(1L);

		OrderCreateCommand command = OrderCreateCommand.builder()
			.memberId(1L)
			.idempotencyKey(idempotencyKey)
			.items(List.of(OrderCreateItem.builder().productId(10L).quantity(1).build()))
			.build();

		stubForIdempotencyReserved();
		given(memberRepository.findById(1L)).willReturn(Optional.of(member));
		given(productRepository.findById(10L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> orderService.createOrder(command))
			.isInstanceOf(ProductException.class)
			.satisfies(exception -> {
				ProductException productException = (ProductException) exception;
				assertThat(productException.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
			});
	}

	@DisplayName("재고 차감이 실패하면 주문 생성에 실패한다")
	@Test
	void createOrder_whenStockDecreaseFails_throwException() {
		// given
		Member member = createMember(1L);
		Product product = createProduct(10L, "product-1", 1000);

		OrderCreateCommand command = OrderCreateCommand.builder()
			.memberId(1L)
			.idempotencyKey(idempotencyKey)
			.items(List.of(OrderCreateItem.builder().productId(10L).quantity(1).build()))
			.build();

		stubForIdempotencyReserved();
		given(memberRepository.findById(1L)).willReturn(Optional.of(member));
		given(productRepository.findById(10L)).willReturn(Optional.of(product));
		willThrow(new StockException(StockErrorCode.STOCK_NOT_FOUND))
			.given(stockService)
			.decreaseWithPessimisticLock(10L, 1);

		// when & then
		assertThatThrownBy(() -> orderService.createOrder(command))
			.isInstanceOf(StockException.class)
			.satisfies(exception -> {
				StockException stockException = (StockException) exception;
				assertThat(stockException.getErrorCode()).isEqualTo(StockErrorCode.STOCK_NOT_FOUND);
			});
	}

	private Member createMember(Long memberId) {
		Member member = Member.builder()
			.email("test@example.com")
			.password("password123")
			.username("user1")
			.build();
		ReflectionTestUtils.setField(member, "id", memberId);
		return member;
	}

	private Product createProduct(Long productId, String name, int price) {
		Product product = Product.builder()
			.name(name)
			.price(price)
			.build();
		ReflectionTestUtils.setField(product, "id", productId);
		return product;
	}

	private OrderCreateCommand createDefaultRequest() {
		return OrderCreateCommand.builder()
			.memberId(1L)
			.idempotencyKey(idempotencyKey)
			.items(List.of(
				OrderCreateItem.builder().productId(10L).quantity(2).build(),
				OrderCreateItem.builder().productId(11L).quantity(1).build()
			))
			.build();
	}

	private void stubForSuccess(Member member, Product product1, Product product2) {
		given(memberRepository.findById(1L)).willReturn(Optional.of(member));
		given(productRepository.findById(10L)).willReturn(Optional.of(product1));
		given(productRepository.findById(11L)).willReturn(Optional.of(product2));
		given(orderRepository.save(any(Order.class))).willAnswer(invocation -> {
			Order saved = invocation.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", 100L);
			return saved;
		});
	}

	private void stubForIdempotencyReserved() {
		given(orderIdempotencyStore.reserve(anyLong(), anyString(), any()))
			.willReturn(true);
	}

	private void stubForSuccessBatch(Member member, Product product1, Product product2) {
		given(memberRepository.findById(1L)).willReturn(Optional.of(member));
		given(productRepository.findAllById(List.of(product1.getId(), product2.getId())))
			.willReturn(List.of(product1, product2));
		given(orderRepository.save(any(Order.class))).willAnswer(invocation -> {
			Order saved = invocation.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", 100L);
			return saved;
		});
	}

}
