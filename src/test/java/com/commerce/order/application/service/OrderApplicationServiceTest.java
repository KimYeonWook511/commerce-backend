package com.commerce.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.member.domain.Member;
import com.commerce.member.domain.repository.MemberRepository;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.order.application.port.OrderIdempotencyStore;
import com.commerce.order.application.dto.OrderCreateItem;
import com.commerce.order.application.dto.OrderCreateCommand;
import com.commerce.order.application.dto.OrderCancelResult;
import com.commerce.order.application.dto.OrderCreateResult;
import com.commerce.order.application.usecase.OrderCreateUseCase;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.domain.repository.ProductRepository;
import com.commerce.stock.domain.exception.StockErrorCode;
import com.commerce.stock.domain.exception.StockException;
import com.commerce.stock.application.service.DecreaseStockService;
import com.commerce.stock.application.service.DecreaseStockBatchService;
import com.commerce.stock.application.service.IncreaseStockService;
import com.commerce.stock.application.service.StockDecreaseConcurrencyService;
import com.commerce.stock.application.dto.StockDecreaseBatchCommand;

@ExtendWith(MockitoExtension.class)
class OrderApplicationServiceTest {

	@Mock
	private MemberRepository memberRepository;

	@Mock
	private ProductRepository productRepository;

	@Mock
	private DecreaseStockService decreaseStockService;

	@Mock
	private DecreaseStockBatchService decreaseStockBatchService;

	@Mock
	private IncreaseStockService increaseStockService;

	@Mock
	private StockDecreaseConcurrencyService stockDecreaseConcurrencyService;

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private OrderIdempotencyStore orderIdempotencyStore;

	@Mock
	private OrderCreateService orderCreateService;

	@InjectMocks
	private OrderCreateUseCase orderCreateUseCase;

	@InjectMocks
	private OrderCancelService orderCancelService;

	@InjectMocks
	private OrderConcurrencyService orderConcurrencyService;

	private final String idempotencyKey = "idempotency-key";

	@DisplayName("기본 주문 생성은 멱등성 확인 후 service에 위임한다")
	@Test
	void createOrder_whenValidRequest_delegatesToService() {
		// given
		OrderCreateCommand command = createDefaultRequest();
		stubForIdempotencyReserved();
		OrderCreateResult expected = stubServiceSuccess(command);

		// when
		OrderCreateResult result = orderCreateUseCase.createOrder(command);

		// then
		assertThat(result.getOrderId()).isEqualTo(expected.getOrderId());
		then(orderCreateService).should().execute(eq(command));
		then(orderIdempotencyStore).should().clear(1L, idempotencyKey);
	}

	@DisplayName("같은 상품이 여러 항목으로 들어오면 service에 위임한다")
	@Test
	void createOrder_whenDuplicateProductItems_delegatesToService() {
		// given
		OrderCreateCommand command = OrderCreateCommand.builder()
			.memberId(1L)
			.idempotencyKey(idempotencyKey)
			.items(List.of(
				OrderCreateItem.builder().productId(10L).quantity(2).build(),
				OrderCreateItem.builder().productId(11L).quantity(1).build(),
				OrderCreateItem.builder().productId(10L).quantity(3).build()
			))
			.build();
		stubForIdempotencyReserved();
		stubServiceSuccess(command);

		// when
		orderCreateUseCase.createOrder(command);

		// then
		then(orderCreateService).should().execute(eq(command));
		then(orderIdempotencyStore).should().clear(1L, idempotencyKey);
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
		orderConcurrencyService.createOrderWithoutLock(command);

		// then
		then(stockDecreaseConcurrencyService).should().decrease(10L, 2);
		then(stockDecreaseConcurrencyService).should().decrease(11L, 1);
	}

	@DisplayName("주문 취소를 요청하면 재고가 복구된다")
	@Test
	void cancelOrder_whenInitStatus_restoreStock() {
		// given
		Order order = Order.create(1L);
		order.addOrderItem(10L, 2, 1000);
		ReflectionTestUtils.setField(order, "id", 100L);

		given(orderRepository.findByIdAndMemberIdWithItems(100L, 1L)).willReturn(Optional.of(order));

		// when
		OrderCancelResult result = orderCancelService.cancelOrder(1L, 100L);

		// then
		then(increaseStockService).should().increase(10L, 2);
		assertThat(result.getOrderId()).isEqualTo(100L);
		assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELED);
	}

	@DisplayName("주문 취소 시 재고 복구는 상품 ID 순서로 호출된다")
	@Test
	void cancelOrder_whenMultipleItems_sortByProductId() {
		// given
		Order order = Order.create(1L);
		order.addOrderItem(5L, 1, 1000);
		order.addOrderItem(2L, 1, 1000);
		ReflectionTestUtils.setField(order, "id", 100L);

		given(orderRepository.findByIdAndMemberIdWithItems(100L, 1L)).willReturn(Optional.of(order));

		// when
		orderCancelService.cancelOrder(1L, 100L);

		// then
		InOrder inOrder = org.mockito.Mockito.inOrder(increaseStockService);
		inOrder.verify(increaseStockService).increase(2L, 1);
		inOrder.verify(increaseStockService).increase(5L, 1);
	}

	@DisplayName("주문 상태가 초기 상태가 아니면 취소에 실패한다")
	@Test
	void cancelOrder_whenStatusNotInit_throwException() {
		// given
		Order order = Order.create(1L);
		order.addOrderItem(10L, 1, 1000);
		ReflectionTestUtils.setField(order, "id", 100L);
		ReflectionTestUtils.setField(order, "status", OrderStatus.RECEIVED);

		given(orderRepository.findByIdAndMemberIdWithItems(100L, 1L)).willReturn(Optional.of(order));

		// when & then
		assertThatThrownBy(() -> orderCancelService.cancelOrder(1L, 100L))
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
		Order order = Order.create(2L);
		order.addOrderItem(10L, 1, 1000);
		ReflectionTestUtils.setField(order, "id", 100L);

		given(orderRepository.findByIdAndMemberIdWithItems(100L, 1L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> orderCancelService.cancelOrder(1L, 100L))
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
		orderConcurrencyService.createOrderWithSynchronized(command);

		// then
		then(stockDecreaseConcurrencyService).should().decreaseWithSynchronized(10L, 2);
		then(stockDecreaseConcurrencyService).should().decreaseWithSynchronized(11L, 1);
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
		orderConcurrencyService.createOrderWithSynchronizedAndTransaction(command);

		// then
		then(stockDecreaseConcurrencyService).should().decreaseWithSynchronizedAndTransaction(10L, 2);
		then(stockDecreaseConcurrencyService).should().decreaseWithSynchronizedAndTransaction(11L, 1);
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
		orderConcurrencyService.createOrderWithReentrantLockAndTransaction(command);

		// then
		then(stockDecreaseConcurrencyService).should().decreaseWithReentrantLockAndTransaction(10L, 2);
		then(stockDecreaseConcurrencyService).should().decreaseWithReentrantLockAndTransaction(11L, 1);
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
		orderConcurrencyService.createOrderWithOptimisticLock(command);

		// then
		then(stockDecreaseConcurrencyService).should().decreaseWithOptimisticLock(10L, 2);
		then(stockDecreaseConcurrencyService).should().decreaseWithOptimisticLock(11L, 1);
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
		orderConcurrencyService.createOrderWithPessimisticLock(command);

		// then
		then(decreaseStockService).should().decrease(10L, 2);
		then(decreaseStockService).should().decrease(11L, 1);
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
		orderConcurrencyService.createOrderWithPessimisticLockOrdered(command);

		// then
		then(decreaseStockService).should().decrease(10L, 2);
		then(decreaseStockService).should().decrease(11L, 1);
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
		orderConcurrencyService.createOrderWithPessimisticLockBatch(command);

		// then
		ArgumentCaptor<StockDecreaseBatchCommand> requestCaptor =
			ArgumentCaptor.forClass(StockDecreaseBatchCommand.class);
		then(decreaseStockBatchService).should().decreaseBatch(requestCaptor.capture());
		assertThat(requestCaptor.getValue().getQuantitiesByProductId())
			.containsEntry(10L, 2)
			.containsEntry(11L, 1);
	}

	@DisplayName("Redis reserve 성공 후 DB에 기존 주문이 있으면 기존 주문을 반환한다")
	@Test
	void createOrder_whenReservedAndExistingOrderInDb_returnExistingOrder() {
		// given
		OrderCreateCommand command = OrderCreateCommand.builder()
			.memberId(1L)
			.idempotencyKey(idempotencyKey)
			.items(List.of(OrderCreateItem.builder().productId(10L).quantity(1).build()))
			.build();

		Order existingOrder = Order.create(1L);
		ReflectionTestUtils.setField(existingOrder, "id", 99L);

		stubForIdempotencyReserved();
		given(orderRepository.findByMemberIdAndIdempotencyKey(1L, idempotencyKey))
			.willReturn(Optional.of(existingOrder));

		// when
		OrderCreateResult result = orderCreateUseCase.createOrder(command);

		// then
		assertThat(result.getOrderId()).isEqualTo(99L);
		then(orderCreateService).shouldHaveNoInteractions();
		then(orderIdempotencyStore).should().clear(1L, idempotencyKey);
	}

	@DisplayName("Redis reserve 실패 시 ORDER_IDEMPOTENCY_IN_PROGRESS 예외를 던진다")
	@Test
	void createOrder_whenReserveFailed_throwInProgress() {
		// given
		OrderCreateCommand command = OrderCreateCommand.builder()
			.memberId(1L)
			.idempotencyKey(idempotencyKey)
			.items(List.of(OrderCreateItem.builder().productId(10L).quantity(1).build()))
			.build();

		given(orderIdempotencyStore.reserve(anyLong(), anyString(), any())).willReturn(false);

		// when & then
		assertThatThrownBy(() -> orderCreateUseCase.createOrder(command))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException)ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_IDEMPOTENCY_IN_PROGRESS));

		then(orderCreateService).shouldHaveNoInteractions();
		then(orderIdempotencyStore).should(org.mockito.Mockito.never()).clear(anyLong(), anyString());
	}

	@DisplayName("service가 예외를 던지면 멱등키를 clear하고 예외를 재발생한다")
	@Test
	void createOrder_whenServiceThrows_clearAndRethrow() {
		// given
		OrderCreateCommand command = OrderCreateCommand.builder()
			.memberId(1L)
			.idempotencyKey(idempotencyKey)
			.items(List.of(OrderCreateItem.builder().productId(10L).quantity(1).build()))
			.build();

		stubForIdempotencyReserved();
		willThrow(new StockException(StockErrorCode.STOCK_NOT_FOUND))
			.given(orderCreateService)
			.execute(eq(command));

		// when & then
		assertThatThrownBy(() -> orderCreateUseCase.createOrder(command))
			.isInstanceOf(StockException.class);

		then(orderIdempotencyStore).should().clear(1L, idempotencyKey);
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
			.status(ProductStatus.ON_SALE)
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

	private void stubForIdempotencyReserved() {
		given(orderIdempotencyStore.reserve(anyLong(), anyString(), any()))
			.willReturn(true);
	}

	private OrderCreateResult stubServiceSuccess(OrderCreateCommand command) {
		Order order = Order.create(1L);
		ReflectionTestUtils.setField(order, "id", 100L);
		OrderCreateResult result = OrderCreateResult.from(order);
		given(orderCreateService.execute(eq(command))).willReturn(result);
		return result;
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
