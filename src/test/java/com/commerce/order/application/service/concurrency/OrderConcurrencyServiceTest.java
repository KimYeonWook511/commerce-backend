package com.commerce.order.application.service.concurrency;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.UUID;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.commerce.order.application.service.OrderCancelService;
import com.commerce.order.application.service.OrderConcurrencyService;
import com.commerce.order.application.service.OrderCreateService;
import com.commerce.member.domain.Member;
import com.commerce.order.application.port.OrderIdempotencyStore;
import com.commerce.order.application.command.OrderCreateItem;
import com.commerce.order.application.command.OrderCreateCommand;
import com.commerce.order.application.result.OrderCreateResult;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.domain.exception.StockErrorCode;
import com.commerce.stock.domain.exception.StockException;
import com.commerce.order.domain.OrderStatus;
import org.springframework.dao.OptimisticLockingFailureException;

import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.stock.infrastructure.persistence.support.StockPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;

@Tag("concurrency")
@SpringBootTest
@ActiveProfiles("test")
@Import({PersistenceCleanupTestSupport.class, MemberPersistenceTestSupport.class, ProductPersistenceTestSupport.class, StockPersistenceTestSupport.class, OrderPersistenceTestSupport.class})
@TestPropertySource(properties = {
	"spring.datasource.hikari.maximum-pool-size=100",
	"spring.datasource.hikari.minimum-idle=30",
	"spring.datasource.hikari.connection-timeout=30000"
})
class OrderConcurrencyServiceTest {

	@Autowired
	private OrderConcurrencyService orderConcurrencyService;

	@Autowired
	private OrderCreateService orderCreateService;

	@Autowired
	private OrderCancelService orderCancelService;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@Autowired
	private MemberPersistenceTestSupport memberPersistence;

	@Autowired
	private ProductPersistenceTestSupport productPersistence;

	@Autowired
	private StockPersistenceTestSupport stockPersistence;

	@Autowired
	private OrderPersistenceTestSupport orderPersistence;

	@MockitoBean
	private OrderIdempotencyStore orderIdempotencyStore;

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(
			memberPersistence, productPersistence, stockPersistence, orderPersistence
		);
	}

	@BeforeEach
	void setUpIdempotencyStore() {
		given(orderIdempotencyStore.reserve(anyLong(), anyString(), any()))
			.willReturn(true);
	}

	@DisplayName("동시 요청 상황에서 락 없이 재고를 차감하면 일부 주문이 실패할 수 있다")
	@Test
	void createOrderWithoutLock_whenConcurrent_allowPartialSuccess() throws Exception {
		// given
		int threadCount = 50;
		Member member = memberPersistence.save(createMember());
		Product product = productPersistence.save(createProduct("order-product-no-lock", 1000));
		stockPersistence.save(createStock(product, threadCount));
		OrderCreateCommand command = createRequest(member.getId(), product.getId(), 1);

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		runConcurrent(threadCount, () -> orderConcurrencyService.createOrderWithoutLock(command), errors);

		// then
		Stock updated = stockPersistence.findByProductId(product.getId()).orElseThrow();
		long orderCount = orderPersistence.count();
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
		Member member = memberPersistence.save(createMember());
		Product product = productPersistence.save(createProduct("order-product-sync", 1000));
		stockPersistence.save(createStock(product, threadCount));
		OrderCreateCommand command = createRequest(member.getId(), product.getId(), 1);

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		runConcurrent(threadCount, () -> orderConcurrencyService.createOrderWithSynchronizedAndTransaction(command), errors);

		// then
		Stock updated = stockPersistence.findByProductId(product.getId()).orElseThrow();
		assertThat(updated.getQuantity()).isZero();
		assertThat(orderPersistence.count()).isEqualTo(threadCount);
		assertThat(errors).isEmpty();
	}

	@DisplayName("동시 요청 상황에서 ReentrantLock+트랜잭션 차감 방식으로 재고가 0이 된다")
	@Test
	void createOrderWithReentrantLockAndTransaction_whenConcurrent_remainZero() throws Exception {
		// given
		int threadCount = 50;
		Member member = memberPersistence.save(createMember());
		Product product = productPersistence.save(createProduct("order-product-reentrant", 1000));
		stockPersistence.save(createStock(product, threadCount));
		OrderCreateCommand command = createRequest(member.getId(), product.getId(), 1);

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		runConcurrent(threadCount, () -> orderConcurrencyService.createOrderWithReentrantLockAndTransaction(command), errors);

		// then
		Stock updated = stockPersistence.findByProductId(product.getId()).orElseThrow();
		assertThat(updated.getQuantity()).isZero();
		assertThat(orderPersistence.count()).isEqualTo(threadCount);
		assertThat(errors).isEmpty();
	}

	@DisplayName("동시 요청 상황에서 낙관적 락 재시도 실패로 재고가 남을 수 있다")
	@Test
	void createOrderWithOptimisticLock_whenConcurrent_allowRemainingStock() throws Exception {
		// given
		int threadCount = 50;
		Member member = memberPersistence.save(createMember());
		Product product = productPersistence.save(createProduct("order-product-optimistic", 1000));
		stockPersistence.save(createStock(product, threadCount));
		OrderCreateCommand command = createRequest(member.getId(), product.getId(), 1);

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		runConcurrent(threadCount, () -> orderConcurrencyService.createOrderWithOptimisticLock(command), errors);

		// then
		Stock updated = stockPersistence.findByProductId(product.getId()).orElseThrow();
		long orderCount = orderPersistence.count();
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
		Member member = memberPersistence.save(createMember());
		Product product = productPersistence.save(createProduct("order-product-pessimistic", 1000));
		stockPersistence.save(createStock(product, threadCount));
		OrderCreateCommand command = createRequest(member.getId(), product.getId(), 1);

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		runConcurrent(threadCount, () -> orderConcurrencyService.createOrderWithPessimisticLock(command), errors);

		// then
		Stock updated = stockPersistence.findByProductId(product.getId()).orElseThrow();
		assertThat(updated.getQuantity()).isZero();
		assertThat(orderPersistence.count()).isEqualTo(threadCount);
		assertThat(errors).isEmpty();
	}

	@DisplayName("주문 생성 시 재고보다 많은 동시 요청은 재고 수량만큼만 성공한다")
	@Test
	void createOrder_whenConcurrentExceedsStock_allowOnlyAvailableQuantity() throws Exception {
		// given
		int threadCount = 50;
		int stockQuantity = 30;
		Member member = memberPersistence.save(createMember());
		Product product = productPersistence.save(createProduct("order-product", 1000));
		stockPersistence.save(createStock(product, stockQuantity));

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		AtomicInteger sequence = new AtomicInteger(0);
		runConcurrent(threadCount, () -> {
			String idempotencyKey = "idempotency-" + sequence.incrementAndGet();
			OrderCreateCommand command =
				createRequest(member.getId(), product.getId(), 1, idempotencyKey);
			orderCreateService.createOrder(command);
		}, errors);

		// then
		long orderCount = orderPersistence.count();
		Stock updated = stockPersistence.findByProductId(product.getId()).orElseThrow();

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
		Member member = memberPersistence.save(createMember());
		Product product = productPersistence.save(createProduct("cancel-product", 1000));
		stockPersistence.save(createStock(product, 5));

		OrderCreateResult created = orderCreateService.createOrder(
			createRequest(member.getId(), product.getId(), 2, "cancel-key")
		);

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		runConcurrent(threadCount, () -> orderCancelService.cancelOrder(member.getId(), created.getOrderId()), errors);

		// then
		Stock updated = stockPersistence.findByProductId(product.getId()).orElseThrow();
		assertThat(updated.getQuantity()).isEqualTo(5);
		assertThat(orderPersistence.findById(created.getOrderId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.CANCELED);
		assertThat(errors).hasSize(threadCount - 1)
			.allSatisfy(error ->
				assertThat(error).isInstanceOf(OptimisticLockingFailureException.class)
			);
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
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		return Member.builder()
			.email("order-concurrency-" + suffix + "@example.com")
			.password("password123")
			.username("u" + suffix)
			.build();
	}

	private Product createProduct(String name, int price) {
		return Product.builder()
			.name(name)
			.price(price)
			.status(ProductStatus.ON_SALE)
			.build();
	}

	private Stock createStock(Product product, int quantity) {
		return Stock.builder()
			.productId(product.getId())
			.quantity(quantity)
			.build();
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
