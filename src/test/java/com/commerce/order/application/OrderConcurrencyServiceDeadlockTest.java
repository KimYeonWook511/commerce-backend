package com.commerce.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.*;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.commerce.member.domain.Member;
import com.commerce.member.repository.MemberRepository;
import com.commerce.order.infrastructure.JpaOrderRepository;
import com.commerce.order.application.command.OrderCreateItem;
import com.commerce.order.application.command.OrderCreateCommand;
import com.commerce.order.infrastructure.JpaOrderItemRepository;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.JpaProductRepository;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.repository.StockRepository;
import com.commerce.stock.application.StockInventoryService;

@Tag("concurrency")
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
	"spring.datasource.url=jdbc:h2:mem:testdb;LOCK_TIMEOUT=1000",
	"spring.datasource.hikari.maximum-pool-size=10",
	"spring.datasource.hikari.minimum-idle=2",
	"spring.datasource.hikari.connection-timeout=30000"
})
class OrderConcurrencyServiceDeadlockTest {

	@Autowired
	private OrderConcurrencyService orderConcurrencyService;

	@MockitoSpyBean
	private StockInventoryService stockService;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private JpaProductRepository productRepository;

	@Autowired
	private StockRepository stockRepository;

	@Autowired
	private JpaOrderRepository orderRepository;

	@Autowired
	private JpaOrderItemRepository orderItemRepository;

	@AfterEach
	void tearDown() {
		orderItemRepository.deleteAllInBatch();
		orderRepository.deleteAllInBatch();
		stockRepository.deleteAllInBatch();
		productRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
	}

	@DisplayName("동시 요청에서 반대 순서로 락을 잡으면 락 대기/타임아웃이 발생할 수 있다")
	@Test
	void createOrderWithPessimisticLock_whenOppositeOrder_mayFailWithLockTimeout() throws Exception {
		// given
		Member member = createMember();
		Product product1 = createProduct("order-product-pessimistic-1", 1000);
		Product product2 = createProduct("order-product-pessimistic-2", 1500);
		createStock(product1, 2);
		createStock(product2, 2);

		OrderCreateCommand requestA = OrderCreateCommand.builder()
			.memberId(member.getId())
			.items(List.of(
				OrderCreateItem.builder().productId(product1.getId()).quantity(1).build(),
				OrderCreateItem.builder().productId(product2.getId()).quantity(1).build()
			))
			.build();
		OrderCreateCommand requestB = OrderCreateCommand.builder()
			.memberId(member.getId())
			.items(List.of(
				OrderCreateItem.builder().productId(product2.getId()).quantity(1).build(),
				OrderCreateItem.builder().productId(product1.getId()).quantity(1).build()
			))
			.build();

		CountDownLatch firstLockReady = new CountDownLatch(2);
		ThreadLocal<Integer> callCount = ThreadLocal.withInitial(() -> 1);
		willAnswer(invocation -> {
			Object result = invocation.callRealMethod();
			int count = callCount.get();
			if (count == 1) {
				firstLockReady.countDown();
				boolean released = firstLockReady.await(10, TimeUnit.SECONDS);
				assertThat(released).isTrue(); // firstLock 해제 실패
			}
			callCount.set(count + 1);
			return result;
		}).given(stockService).decrease(anyLong(), anyInt());

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		AtomicInteger sequence = new AtomicInteger();
		try {
			runConcurrent(2, () -> {
				int index = sequence.getAndIncrement();
				if (index == 0) {
					orderConcurrencyService.createOrderWithPessimisticLock(requestA);
				} else {
					orderConcurrencyService.createOrderWithPessimisticLock(requestB);
				}
			}, errors);
		} finally {
			// @SpringBootTest는 테스트 컨텍스트를 캐시함
			// 다른 테스트에 영향이 남지 않게 스텁 제거하기
			reset(stockService);
		}

		// then
		long orderCount = orderRepository.count();
		assertThat(errors).isNotEmpty();
		assertThat(orderCount).isLessThan(2L);
	}

	@DisplayName("동시 요청에서 반대 순서로 요청해도 데드락 없이 처리된다")
	@Test
	void createOrderWithPessimisticLockOrdered_whenOppositeOrder_avoidDeadlock() throws Exception {
		// given
		Member member = createMember();
		Product product1 = createProduct("order-product-pessimistic-ordered-1", 1000);
		Product product2 = createProduct("order-product-pessimistic-ordered-2", 1500);
		Product product3 = createProduct("order-product-pessimistic-ordered-3", 2000);
		Product product4 = createProduct("order-product-pessimistic-ordered-4", 2500);
		createStock(product1, 2);
		createStock(product2, 2);
		createStock(product3, 2);
		createStock(product4, 2);

		OrderCreateCommand requestA = OrderCreateCommand.builder()
			.memberId(member.getId())
			.items(List.of(
				OrderCreateItem.builder().productId(product1.getId()).quantity(1).build(),
				OrderCreateItem.builder().productId(product2.getId()).quantity(1).build(),
				OrderCreateItem.builder().productId(product3.getId()).quantity(1).build()
			))
			.build();
		OrderCreateCommand requestB = OrderCreateCommand.builder()
			.memberId(member.getId())
			.items(List.of(
				OrderCreateItem.builder().productId(product4.getId()).quantity(1).build(),
				OrderCreateItem.builder().productId(product2.getId()).quantity(1).build(),
				OrderCreateItem.builder().productId(product1.getId()).quantity(1).build()
			))
			.build();

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		AtomicInteger sequence = new AtomicInteger();
		runConcurrent(2, () -> {
			int index = sequence.getAndIncrement();
			if (index == 0) {
				orderConcurrencyService.createOrderWithPessimisticLockOrdered(requestA);
			} else {
				orderConcurrencyService.createOrderWithPessimisticLockOrdered(requestB);
			}
		}, errors);

		// then
		long orderCount = orderRepository.count();
		assertThat(errors).isEmpty();
		assertThat(orderCount).isEqualTo(2L);
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
			boolean completed = doneLatch.await(20, TimeUnit.SECONDS);
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
}
