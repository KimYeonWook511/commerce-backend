package com.commerce.order.application.service.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.commerce.order.application.service.ExpireOrderService;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.outbox.infrastructure.persistence.support.OutboxPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

/**
 * 만료 배치가 같은 주문을 동시에 정리할 때 지켜야 하는 것을 실제 DB 위에서 확인한다. 주문이 한 번만
 * 취소되는 것은 저장 시점의 낙관 락 충돌이 만들고, 재고 복구 outbox 이벤트가 정확히 하나만 남는 것은
 * 진 쪽 트랜잭션이 그 이벤트까지 함께 되돌리는 것이 만든다. 리포지토리나 outbox를 대역으로 바꾸면 그
 * 경합의 무대 자체가 사라져 이 두 가지를 확인할 수 없다.
 */
@Tag("concurrency")
@Tag("docker")
@SpringBootTest
@ActiveProfiles("test")
@Import({
	PersistenceCleanupTestSupport.class,
	OrderPersistenceTestSupport.class,
	OutboxPersistenceTestSupport.class
})
class ExpireOrderServiceConcurrencyTest {

	private static final int THREADS = 2;

	@Autowired
	private ExpireOrderService expireOrderService;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@Autowired
	private OrderPersistenceTestSupport orderPersistence;

	@Autowired
	private OutboxPersistenceTestSupport outboxPersistence;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
	}

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(orderPersistence, outboxPersistence);
	}

	@DisplayName("같은 주문에 만료 처리가 동시에 들어와도 한 번만 취소되고 재고 복구 outbox 이벤트도 하나만 남는다")
	@Test
	void expireOrder_whenConcurrentRequests_convergesToOneCancellation() throws Exception {
		// given
		Order order = Order.create(1L);
		order.addOrderItem(10L, 2, 1000);
		Order saved = orderPersistence.saveAndFlush(order);
		LocalDateTime requestedAt = LocalDateTime.now();

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		runConcurrent(() -> expireOrderService.expireOrder(saved.getId(), requestedAt), errors);

		// then
		assertThat(orderPersistence.getOrderStatusById(saved.getId())).isEqualTo(OrderStatus.CANCELED);
		assertThat(outboxPersistence.count()).isEqualTo(1L);
		assertThat(errors).hasSize(THREADS - 1);
	}

	private void runConcurrent(Runnable task, ConcurrentLinkedQueue<Throwable> errors) throws InterruptedException {
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);
		CountDownLatch readyLatch = new CountDownLatch(THREADS);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(THREADS);

		try {
			for (int i = 0; i < THREADS; i++) {
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
}
