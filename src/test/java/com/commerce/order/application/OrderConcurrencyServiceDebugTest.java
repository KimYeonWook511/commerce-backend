package com.commerce.order.application;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

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

@Tag("concurrency")
@SpringBootTest
@ActiveProfiles("test")
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
	private MemberRepository memberRepository;

	@Autowired
	private JpaProductRepository productRepository;

	@Autowired
	private StockRepository stockRepository;

	@Autowired
	private JpaOrderRepository orderRepository;

	@Autowired
	private JpaOrderItemRepository orderItemRepository;

	@Autowired
	private DataSource dataSource;

	@AfterEach
	void tearDown() {
		orderItemRepository.deleteAllInBatch();
		orderRepository.deleteAllInBatch();
		stockRepository.deleteAllInBatch();
		productRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
	}

	@DisplayName("동시 주문 시 2초와 30초 지연이 갈리는 원인을 디버깅한다")
	@Test
	void createOrderWithSynchronizedAndTransaction_whenConcurrentRequests_debugLatencyGapBetween2sAnd30s() throws Exception {
		// given
		int threadCount = 50;
		Member member = createMember();
		Product product = createProduct("order-product-sync", 1000);
		createStock(product, threadCount);
		OrderCreateCommand command = createRequest(member.getId(), product.getId(), 1);

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		runConcurrent(threadCount, () -> orderConcurrencyService.createOrderWithSynchronizedAndTransaction(command), errors);

		// then
		Stock updated = stockRepository.findByProductId(product.getId()).orElseThrow();
		assertThat(updated.getQuantity()).isZero();
		assertThat(orderRepository.count()).isEqualTo(threadCount);
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
}
