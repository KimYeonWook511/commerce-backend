package com.commerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

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
import com.commerce.order.repository.OrderRepository;
import com.commerce.order.service.request.OrderCreateItem;
import com.commerce.order.service.request.OrderCreateServiceRequest;
import com.commerce.orderitem.repository.OrderItemRepository;
import com.commerce.product.domain.Product;
import com.commerce.product.repository.ProductRepository;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.repository.StockRepository;
import com.commerce.stock.service.StockService;

@Tag("concurrency")
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
	"spring.datasource.url=jdbc:h2:mem:testdb;LOCK_TIMEOUT=1000",
	"spring.datasource.hikari.maximum-pool-size=10",
	"spring.datasource.hikari.minimum-idle=2",
	"spring.datasource.hikari.connection-timeout=30000"
})
class OrderServiceDeadLockTest {

	@Autowired
	private OrderService orderService;

	@MockitoSpyBean
	private StockService stockService;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private StockRepository stockRepository;

	@Autowired
	private OrderRepository orderRepository;

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

	@DisplayName("동시 요청에서 반대 순서로 락을 잡으면 락 대기/타임아웃이 발생할 수 있다")
	@Test
	void createOrderWithPessimisticLock_whenOppositeOrder_mayFailWithLockTimeout() throws Exception {
		// given
		Member member = createMember();
		Product product1 = createProduct("order-product-pessimistic-1", 1000);
		Product product2 = createProduct("order-product-pessimistic-2", 1500);
		createStock(product1, 2);
		createStock(product2, 2);

		OrderCreateServiceRequest requestA = OrderCreateServiceRequest.builder()
			.memberId(member.getId())
			.items(List.of(
				OrderCreateItem.builder().productId(product1.getId()).quantity(1).build(),
				OrderCreateItem.builder().productId(product2.getId()).quantity(1).build()
			))
			.build();
		OrderCreateServiceRequest requestB = OrderCreateServiceRequest.builder()
			.memberId(member.getId())
			.items(List.of(
				OrderCreateItem.builder().productId(product2.getId()).quantity(1).build(),
				OrderCreateItem.builder().productId(product1.getId()).quantity(1).build()
			))
			.build();

		CountDownLatch firstLockReady = new CountDownLatch(2);
		ThreadLocal<Integer> callCount = ThreadLocal.withInitial(() -> 1);
		doAnswer(invocation -> {
			Object result = invocation.callRealMethod();
			int count = callCount.get();
			if (count == 1) {
				firstLockReady.countDown();
				boolean released = firstLockReady.await(10, TimeUnit.SECONDS);
				assertThat(released).isTrue(); // firstLock 해제 실패
			}
			callCount.set(count + 1);
			return result;
		}).when(stockService).decreaseWithPessimisticLock(anyLong(), anyInt());

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		AtomicInteger sequence = new AtomicInteger();
		try {
			runConcurrent(2, () -> {
				int index = sequence.getAndIncrement();
				if (index == 0) {
					orderService.createOrderWithPessimisticLock(requestA);
				} else {
					orderService.createOrderWithPessimisticLock(requestB);
				}
			}, errors);
		} finally {
			// @SpringBootTest는 테스트 컨텍스트를 캐시함
			// 다른 테스트에 영향이 남지 않게 doAnswer 스텁 제거하기
			reset(stockService);
		}

		// then
		long orderCount = orderRepository.count();
		assertThat(errors).isNotEmpty();
		assertThat(orderCount).isLessThan(2L);
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
