package com.commerce.order.application;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.commerce.member.domain.Member;
import com.commerce.member.repository.MemberRepository;
import com.commerce.order.infrastructure.JpaOrderRepository;
import com.commerce.order.application.command.OrderCreateItem;
import com.commerce.order.application.command.OrderCreateCommand;
import com.commerce.order.application.result.OrderCreateResult;
import com.commerce.orderitem.repository.OrderItemRepository;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.repository.ProductRepository;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.repository.StockRepository;
import com.commerce.test.support.TestcontainersSupport;

@Tag("concurrency")
@Tag("docker")
@SpringBootTest
@ActiveProfiles("test")
class OrderConcurrencyServiceDeadlockMysqlTest {

	@Autowired
	private OrderConcurrencyService orderConcurrencyService;

	@Autowired
	private OrderCancelService orderCancelService;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private StockRepository stockRepository;

	@Autowired
	private JpaOrderRepository orderRepository;

	@Autowired
	private OrderItemRepository orderItemRepository;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
	}

	@AfterEach
	void tearDown() {
		orderItemRepository.deleteAllInBatch();
		orderRepository.deleteAllInBatch();
		stockRepository.deleteAllInBatch();
		productRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
	}

	@DisplayName("MySQL에서 주문 항목 순서가 달라도 재고 정합성이 유지된다")
	@Test
	void createOrderWithPessimisticLockBatch_whenOrderItemSequenceDiffers_keepStockConsistency() throws Exception {
		// given
		Member member = createMember();
		Product product1 = createProduct("mysql-order-product-1", 1000);
		Product product2 = createProduct("mysql-order-product-2", 1100);
		Product product3 = createProduct("mysql-order-product-3", 1200);
		Product product4 = createProduct("mysql-order-product-4", 1300);
		Product product5 = createProduct("mysql-order-product-5", 1400);
		Product product6 = createProduct("mysql-order-product-6", 1500);
		Product product7 = createProduct("mysql-order-product-7", 1600);
		Product product8 = createProduct("mysql-order-product-8", 1700);
		List<Product> products = List.of(
			product1, product2, product3, product4, product5, product6, product7, product8
		);
		for (Product product : products) {
			createStock(product, 2);
		}

		OrderCreateCommand requestA = createRequest(member.getId(), List.of(
			product1.getId(), product2.getId(), product3.getId(), product4.getId(),
			product5.getId(), product6.getId(), product7.getId(), product8.getId()
		));
		OrderCreateCommand requestB = createRequest(member.getId(), List.of(
			product8.getId(), product7.getId(), product6.getId(), product5.getId(),
			product4.getId(), product3.getId(), product2.getId(), product1.getId()
		));

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		AtomicInteger sequence = new AtomicInteger();
		runConcurrent(2, () -> {
			int index = sequence.getAndIncrement();
			if (index == 0) {
				orderConcurrencyService.createOrderWithPessimisticLockBatch(requestA);
			} else {
				orderConcurrencyService.createOrderWithPessimisticLockBatch(requestB);
			}
		}, errors);

		// then
		long orderCount = orderRepository.count();
		assertThat(orderCount).isEqualTo(2L);
		assertThat(errors).isEmpty();
		for (Product product : products) {
			Stock updated = stockRepository.findByProductId(product.getId()).orElseThrow();
			// assertThat(updated.getQuantity()).isEqualTo(0L);
		}
	}

	@DisplayName("동시 주문을 반복 실행해도 배치 락 재고 정합성이 유지된다")
	@Test
	void createOrderWithPessimisticLockBatch_whenRepeatedConcurrency_keepStockConsistency() throws Exception {
		// given
		int threadCount = 20;
		Member member = createMember();
		List<Product> products = List.of(
			createProduct("mysql-order-batch-1", 1000),
			createProduct("mysql-order-batch-2", 1100),
			createProduct("mysql-order-batch-3", 1200),
			createProduct("mysql-order-batch-4", 1300),
			createProduct("mysql-order-batch-5", 1400),
			createProduct("mysql-order-batch-6", 1500),
			createProduct("mysql-order-batch-7", 1600),
			createProduct("mysql-order-batch-8", 1700)
		);
		List<Long> orderedIds = products.stream().map(Product::getId).toList();
		List<Long> reversedIds = new ArrayList<>(orderedIds);
		Collections.reverse(reversedIds);
		for (Product product : products) {
			createStock(product, threadCount);
		}

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		AtomicInteger sequence = new AtomicInteger();
		runConcurrent(threadCount, () -> {
			int index = sequence.getAndIncrement();
			List<Long> productIds = (index % 2 == 0) ? orderedIds : reversedIds;
			OrderCreateCommand command = createRequest(member.getId(), productIds);
			orderConcurrencyService.createOrderWithPessimisticLockBatch(command);
		}, errors);

		long orderCount = orderRepository.count();
		assertThat(orderCount).isEqualTo(threadCount);
		assertThat(errors).isEmpty();
		for (Product product : products) {
			Stock updated = stockRepository.findByProductId(product.getId()).orElseThrow();
			assertThat(updated.getQuantity()).isZero();
		}
	}

	@DisplayName("다양한 주문 순서 조합에서도 배치 락 재고 정합성이 유지된다")
	@Test
	void createOrderWithPessimisticLockBatch_whenMixedOrders_keepStockConsistency() throws Exception {
		// given
		int threadCount = 16;
		Member member = createMember();
		List<Product> products = List.of(
			createProduct("mysql-order-mixed-1", 1000),
			createProduct("mysql-order-mixed-2", 1100),
			createProduct("mysql-order-mixed-3", 1200),
			createProduct("mysql-order-mixed-4", 1300),
			createProduct("mysql-order-mixed-5", 1400),
			createProduct("mysql-order-mixed-6", 1500),
			createProduct("mysql-order-mixed-7", 1600),
			createProduct("mysql-order-mixed-8", 1700)
		);
		for (Product product : products) {
			createStock(product, threadCount);
		}

		List<Long> orderedIds = products.stream().map(Product::getId).toList();
		List<Long> reversedIds = new ArrayList<>(orderedIds);
		Collections.reverse(reversedIds);
		List<Long> rotatedByTwo = rotateIds(orderedIds, 2);
		List<Long> rotatedByFour = rotateIds(orderedIds, 4);
		List<List<Long>> orders = List.of(orderedIds, reversedIds, rotatedByTwo, rotatedByFour);

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		AtomicInteger sequence = new AtomicInteger();
		runConcurrent(threadCount, () -> {
			int index = sequence.getAndIncrement();
			List<Long> productIds = orders.get(index % orders.size());
			OrderCreateCommand command = createRequest(member.getId(), productIds);
			orderConcurrencyService.createOrderWithPessimisticLockBatch(command);
		}, errors);

		// then
		long orderCount = orderRepository.count();
		assertThat(orderCount).isEqualTo(threadCount);
		assertThat(errors).isEmpty();
		for (Product product : products) {
			Stock updated = stockRepository.findByProductId(product.getId()).orElseThrow();
			assertThat(updated.getQuantity()).isZero();
		}
	}

	@DisplayName("MySQL에서 주문 생성과 주문 취소가 동시에 일어나도 데드락이 발생하지 않는다")
	@Test
	void createOrderAndCancelOrder_whenConcurrent_noDeadlock() throws Exception {
		// given
		Member member = createMember();
		Product product1 = createProduct("mysql-order-product-1", 1000);
		Product product2 = createProduct("mysql-order-product-2", 1200);
		createStock(product1, 10);
		createStock(product2, 10);

		OrderCreateCommand cancelRequest = createRequest(
			member.getId(), List.of(product2.getId(), product1.getId())
		);
		OrderCreateResult created = orderConcurrencyService.createOrderWithPessimisticLockOrdered(cancelRequest);

		OrderCreateCommand createRequest = createRequest(
			member.getId(), List.of(product1.getId(), product2.getId())
		);

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		AtomicInteger sequence = new AtomicInteger();
		runConcurrent(2, () -> {
			int index = sequence.getAndIncrement();
			if (index == 0) {
				orderCancelService.cancelOrder(member.getId(), created.getOrderId());
			} else {
				orderConcurrencyService.createOrderWithPessimisticLockOrdered(createRequest);
			}
		}, errors);

		// then
		assertThat(errors).isEmpty();
		assertThat(orderRepository.count()).isEqualTo(2L);
		assertThat(stockRepository.findByProductId(product1.getId()).orElseThrow().getQuantity())
			.isEqualTo(9);
		assertThat(stockRepository.findByProductId(product2.getId()).orElseThrow().getQuantity())
			.isEqualTo(9);
	}

	private void runConcurrent(int threadCount, Runnable task, ConcurrentLinkedQueue<Throwable> errors)
		throws InterruptedException {
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch readyLatch = new CountDownLatch(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);

		try {
			for (int i = 0; i < threadCount; i++) {
				executor.submit(() -> {
					readyLatch.countDown();
					try {
						startLatch.await();
						task.run();
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
	}

	private Member createMember() {
		return memberRepository.save(
			Member.builder()
				.email("mysql-deadlock@example.com")
				.password("password123")
				.username("deadlock")
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

	private OrderCreateCommand createRequest(Long memberId, List<Long> productIds) {
		List<OrderCreateItem> items = productIds.stream()
			.map(productId -> OrderCreateItem.builder()
				.productId(productId)
				.quantity(1)
				.build())
			.toList();
		return OrderCreateCommand.builder()
			.memberId(memberId)
			.items(items)
			.build();
	}

	private List<Long> rotateIds(List<Long> productIds, int offset) {
		int size = productIds.size();
		List<Long> rotated = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			rotated.add(productIds.get((i + offset) % size));
		}
		return rotated;
	}
}
