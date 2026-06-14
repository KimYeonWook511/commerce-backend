package com.commerce.order.application.service.concurrency;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.commerce.order.application.service.OrderCancelService;
import com.commerce.order.application.service.OrderConcurrencyService;
import com.commerce.order.application.service.OrderCreateService;
import com.commerce.member.domain.Member;
import com.commerce.order.application.dto.OrderCreateItem;
import com.commerce.order.application.dto.OrderCreateCommand;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.stock.domain.Stock;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.stock.infrastructure.persistence.support.StockPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;

@Tag("concurrency")
@Tag("learning")
@Disabled("디버그용 — 주문 동시성 흐름 관찰을 위해 Thread.sleep 등 디버그 흔적 포함. 운영 검증 대상 아님")
@SpringBootTest
@ActiveProfiles("test")
@Import({PersistenceCleanupTestSupport.class, MemberPersistenceTestSupport.class, ProductPersistenceTestSupport.class, StockPersistenceTestSupport.class, OrderPersistenceTestSupport.class})
@TestPropertySource(properties = {
	"spring.datasource.hikari.maximum-pool-size=51",
	"spring.datasource.hikari.minimum-idle=10",
	"spring.datasource.hikari.connection-timeout=40000",
	"logging.level.com.zaxxer.hikari=TRACE",
	"logging.level.com.zaxxer.hikari.pool=TRACE"
})
class OrderConcurrencyServiceDebugTest {

	@Autowired
	private OrderConcurrencyService orderConcurrencyService;

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

	@Autowired
	private DataSource dataSource;

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(
			memberPersistence, productPersistence, stockPersistence, orderPersistence
		);
	}

	@DisplayName("동시 주문 시 2초와 30초 지연이 갈리는 원인을 디버깅한다")
	@Test
	void createOrderWithSynchronizedAndTransaction_whenConcurrentRequests_debugLatencyGapBetween2sAnd30s() throws Exception {
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

	private void runConcurrent(
		int threadCount, Runnable task, ConcurrentLinkedQueue<Throwable> errors
	) throws InterruptedException {
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch readyLatch = new CountDownLatch(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);
		ConcurrentLinkedDeque<Long> durations = new ConcurrentLinkedDeque<>();

		ExecutorService monitorExecutor = Executors.newSingleThreadExecutor();
		long startAt = System.nanoTime();
		monitorExecutor.submit(() -> {
			try {
				while (!Thread.currentThread().isInterrupted()) {
					long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startAt);
					logPoolStatus("tick-" + elapsedMs + "ms");
					Thread.sleep(1000); // 1초씩 쉬기
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});

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
			logPoolStatus("before-start");
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
		monitorExecutor.shutdownNow();

		logPoolStatus("after-finish");

		long max = durations.stream().mapToLong(Long::longValue).max().orElse(0);
		double avg = durations.stream().mapToLong(Long::longValue).average().orElse(0);
		System.out.println("avg=" + avg + "ms, max=" + max + "ms");
	}

	private void logPoolStatus(String phase) {
		try {
			com.zaxxer.hikari.HikariDataSource hikari = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class);
			com.zaxxer.hikari.HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
			System.out.println("pool[" + phase + "] active=" + pool.getActiveConnections()
				+ ", idle=" + pool.getIdleConnections()
				+ ", total=" + pool.getTotalConnections()
				+ ", waiting=" + pool.getThreadsAwaitingConnection());
		} catch (java.sql.SQLException e) {
			throw new IllegalStateException("HikariDataSource unwrap failed", e);
		}
	}

	private Member createMember() {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		return Member.builder()
			.email("order-debug-" + suffix + "@example.com")
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
}
