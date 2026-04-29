package com.commerce.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.commerce.member.domain.Member;
import com.commerce.member.repository.MemberRepository;
import com.commerce.order.repository.OrderRepository;
import com.commerce.order.redis.OrderIdempotencyStore;
import com.commerce.order.service.command.OrderCreateItem;
import com.commerce.order.service.command.OrderCreateCommand;
import com.commerce.order.service.result.OrderCreateResult;
import com.commerce.orderitem.repository.OrderItemRepository;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.repository.ProductRepository;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.exception.StockErrorCode;
import com.commerce.stock.exception.StockException;
import com.commerce.stock.repository.StockRepository;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;

@Tag("concurrency")
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
	"spring.datasource.hikari.maximum-pool-size=100",
	"spring.datasource.hikari.minimum-idle=30",
	"spring.datasource.hikari.connection-timeout=30000"
})
class OrderServiceConcurrencyTest {

	@Autowired
	private OrderService orderService;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private StockRepository stockRepository;

	@Autowired
	private OrderRepository orderRepository;

	@MockitoBean
	private OrderIdempotencyStore orderIdempotencyStore;

	@Autowired
	private OrderItemRepository orderItemRepository;

	@AfterEach
	void tearDown() {
		orderItemRepository.deleteAllInBatch();
		orderRepository.deleteAllInBatch();
		stockRepository.deleteAllInBatch();
		productRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
	}

	@BeforeEach
	void setUpIdempotencyStore() {
		given(orderIdempotencyStore.reserve(anyLong(), anyString(), any()))
			.willReturn(true);
		given(orderIdempotencyStore.getCompletedOrderId(anyLong(), anyString()))
			.willReturn(Optional.empty());
	}

	@DisplayName("동시 요청 상황에서 락 없이 재고를 차감하면 일부 주문이 실패할 수 있다")
	@Test
	void createOrderWithoutLock_whenConcurrent_allowPartialSuccess() throws Exception {
		// given
		int threadCount = 50;
		Member member = createMember();
		Product product = createProduct("order-product-no-lock", 1000);
		createStock(product, threadCount);
		OrderCreateCommand command = createRequest(member.getId(), product.getId(), 1);

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		runConcurrent(threadCount, () -> orderService.createOrderWithoutLock(command), errors);

		// then
		Stock updated = stockRepository.findByProductId(product.getId()).orElseThrow();
		long orderCount = orderRepository.count();
		assertThat(orderCount).isBetween(0L, (long) threadCount);
		assertThat(updated.getQuantity()).isBetween(0, threadCount);
		assertThat(updated.getQuantity() + orderCount).isEqualTo(threadCount);
		assertThat(errors.size()).isEqualTo(threadCount - orderCount);
	}

	@DisplayName("동시 요청 상황에서 동기화+트랜잭션 차감 방식으로 재고가 0이 된다")
	@Test
	void createOrderWithSynchronizedAndTransaction_whenConcurrent_remainZero() throws Exception {
		// given
		int threadCount = 50;
		Member member = createMember();
		Product product = createProduct("order-product-sync", 1000);
		createStock(product, threadCount);
		OrderCreateCommand command = createRequest(member.getId(), product.getId(), 1);

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		runConcurrent(threadCount, () -> orderService.createOrderWithSynchronizedAndTransaction(command), errors);

		// then
		Stock updated = stockRepository.findByProductId(product.getId()).orElseThrow();
		assertThat(updated.getQuantity()).isZero();
		assertThat(orderRepository.count()).isEqualTo(threadCount);
		assertThat(errors).isEmpty();
	}

	@DisplayName("동시 요청 상황에서 ReentrantLock+트랜잭션 차감 방식으로 재고가 0이 된다")
	@Test
	void createOrderWithReentrantLockAndTransaction_whenConcurrent_remainZero() throws Exception {
		// given
		int threadCount = 50;
		Member member = createMember();
		Product product = createProduct("order-product-reentrant", 1000);
		createStock(product, threadCount);
		OrderCreateCommand command = createRequest(member.getId(), product.getId(), 1);

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		runConcurrent(threadCount, () -> orderService.createOrderWithReentrantLockAndTransaction(command), errors);

		// then
		Stock updated = stockRepository.findByProductId(product.getId()).orElseThrow();
		assertThat(updated.getQuantity()).isZero();
		assertThat(orderRepository.count()).isEqualTo(threadCount);
		assertThat(errors).isEmpty();
	}

	@DisplayName("동시 요청 상황에서 낙관적 락 재시도 실패로 재고가 남을 수 있다")
	@Test
	void createOrderWithOptimisticLock_whenConcurrent_allowRemainingStock() throws Exception {
		// given
		int threadCount = 50;
		Member member = createMember();
		Product product = createProduct("order-product-optimistic", 1000);
		createStock(product, threadCount);
		OrderCreateCommand command = createRequest(member.getId(), product.getId(), 1);

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		runConcurrent(threadCount, () -> orderService.createOrderWithOptimisticLock(command), errors);

		// then
		Stock updated = stockRepository.findByProductId(product.getId()).orElseThrow();
		long orderCount = orderRepository.count();
		assertThat(orderCount).isBetween(0L, (long) threadCount);
		assertThat(updated.getQuantity()).isBetween(0, threadCount);
		assertThat(updated.getQuantity() - errors.size()).isZero();
		assertThat(updated.getQuantity() + orderCount).isEqualTo(threadCount);
		assertThat(errors.size()).isEqualTo(threadCount - orderCount);
	}

	@DisplayName("동시 요청 상황에서 비관적 락 차감 방식으로 재고가 0이 된다")
	@Test
	void createOrderWithPessimisticLock_whenConcurrent_remainZero() throws Exception {
		// given
		int threadCount = 50;
		Member member = createMember();
		Product product = createProduct("order-product-pessimistic", 1000);
		createStock(product, threadCount);
		OrderCreateCommand command = createRequest(member.getId(), product.getId(), 1);

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		runConcurrent(threadCount, () -> orderService.createOrderWithPessimisticLock(command), errors);

		// then
		Stock updated = stockRepository.findByProductId(product.getId()).orElseThrow();
		assertThat(updated.getQuantity()).isZero();
		assertThat(orderRepository.count()).isEqualTo(threadCount);
		assertThat(errors).isEmpty();
	}

	@DisplayName("주문 생성 시 재고보다 많은 동시 요청은 재고 수량만큼만 성공한다")
	@Test
	void createOrder_whenConcurrentExceedsStock_allowOnlyAvailableQuantity() throws Exception {
		// given
		int threadCount = 50;
		int stockQuantity = 30;
		Member member = createMember();
		Product product = createProduct("order-product", 1000);
		createStock(product, stockQuantity);

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		AtomicInteger sequence = new AtomicInteger(0);
		runConcurrent(threadCount, () -> {
			String idempotencyKey = "idempotency-" + sequence.incrementAndGet();
			OrderCreateCommand command =
				createRequest(member.getId(), product.getId(), 1, idempotencyKey);
			orderService.createOrder(command);
		}, errors);

		// then
		long orderCount = orderRepository.count();
		Stock updated = stockRepository.findByProductId(product.getId()).orElseThrow();

		assertThat(orderCount).isEqualTo(stockQuantity);
		assertThat(updated.getQuantity()).isZero();
		assertThat(errors).hasSize(threadCount - stockQuantity)
			.allSatisfy(error -> {
				assertThat(error).isInstanceOf(StockException.class);
				StockException stockException = (StockException) error;
				assertThat(stockException.getErrorCode()).isEqualTo(StockErrorCode.OUT_OF_STOCK);
			});
	}

	@DisplayName("같은 주문에 취소 요청이 동시에 와도 한 번만 취소된다")
	@Test
	void cancelOrder_whenConcurrentRequests_onlyOneCancel() throws Exception {
		// given
		int threadCount = 3;
		Member member = createMember();
		Product product = createProduct("cancel-product", 1000);
		createStock(product, 5);

		OrderCreateResult created = orderService.createOrder(
			createRequest(member.getId(), product.getId(), 2, "cancel-key")
		);

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		runConcurrent(threadCount, () -> orderService.cancelOrder(member.getId(), created.getOrderId()), errors);

		// then
		Stock updated = stockRepository.findByProductId(product.getId()).orElseThrow();
		assertThat(updated.getQuantity()).isEqualTo(5);
		assertThat(orderRepository.findById(created.getOrderId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.CANCELED);
		assertThat(errors).hasSize(threadCount - 1)
			.allSatisfy(error -> {
				assertThat(error).isInstanceOf(OrderException.class);
				OrderException orderException = (OrderException) error;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED);
			});
	}

	private void runConcurrent(int threadCount, Runnable task, ConcurrentLinkedQueue<Throwable> errors)
		throws InterruptedException {
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch readyLatch = new CountDownLatch(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);
		ConcurrentLinkedDeque<Long> durations = new ConcurrentLinkedDeque<>();

		try {
			for (int i = 0; i < threadCount; i++) {
				executor.submit(() -> {
					readyLatch.countDown();
					try {
						startLatch.await();
						long start = System.nanoTime();
						task.run();
						long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
						durations.add(durationMs);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} catch (Throwable t) {
						errors.add(t);
					} finally {
						doneLatch.countDown();
					}
				});
			}

			readyLatch.await();
			startLatch.countDown();
			boolean completed = doneLatch.await(60, TimeUnit.SECONDS);
			assertThat(completed).isTrue();
		} finally {
			executor.shutdown();
			boolean terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
			if (!terminated) {
				executor.shutdownNow();
			}
		}

		long max = durations.stream().mapToLong(Long::longValue).max().orElse(0);
		double avg = durations.stream().mapToLong(Long::longValue).average().orElse(0);
		System.out.println("avg=" + avg + "ms, max=" + max + "ms");
	}

	private Member createMember() {
		return memberRepository.save(
			Member.builder()
				.email("test@example.com")
				.password("password123")
				.username("user1")
				.build()
		);
	}

	private Product createProduct(String name, int price) {
		return productRepository.save(
			Product.builder()
				.name(name)
				.price(price)
				.status(ProductStatus.ON_SALE)
				.build()
		);
	}

	private Stock createStock(Product product, int quantity) {
		return stockRepository.save(
			Stock.builder()
				.product(product)
				.quantity(quantity)
				.build()
		);
	}

	private OrderCreateCommand createRequest(Long memberId, Long productId, int quantity) {
		return OrderCreateCommand.builder()
			.memberId(memberId)
			.items(List.of(OrderCreateItem.builder().productId(productId).quantity(quantity).build()))
			.build();
	}

	private OrderCreateCommand createRequest(
		Long memberId,
		Long productId,
		int quantity,
		String idempotencyKey
	) {
		return OrderCreateCommand.builder()
			.memberId(memberId)
			.idempotencyKey(idempotencyKey)
			.items(List.of(OrderCreateItem.builder().productId(productId).quantity(quantity).build()))
			.build();
	}

}
