package com.commerce.stock.application;

import static org.assertj.core.api.Assertions.*;

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
import org.springframework.test.context.ActiveProfiles;

import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.repository.ProductRepository;
import com.commerce.stock.application.command.StockDecreaseBatchCommand;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.repository.StockRepository;

@Tag("concurrency")
@SpringBootTest
@ActiveProfiles("test")
class StockConcurrencyTest {

	@Autowired
	private StockConcurrencyService stockConcurrencyService;

	@Autowired
	private StockInventoryService stockInventoryService;

	@Autowired
	private StockRepository stockRepository;

	@Autowired
	private ProductRepository productRepository;

	@AfterEach
	void tearDown() {
		stockRepository.deleteAllInBatch();
		productRepository.deleteAllInBatch();
	}

	@DisplayName("락 없이 동시에 차감하면 재고가 예상치와 달라질 수 있다")
	@Test
	void decrease_whenConcurrentWithoutLock_resultMayDiffer() throws Exception {
		// given
		Product product = createProduct("test-product", 1000);
		createStock(product, 100);
		int threadCount = 100;

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		measureConcurrent(
			"no-lock",
			threadCount,
			() -> stockConcurrencyService.decrease(product.getId(), 1),
			errors
		);

		// then
		Stock updated = stockRepository.findByProductId(product.getId()).orElseThrow();
		assertThat(updated.getQuantity()).isGreaterThan(0);
	}

	@DisplayName("synchronized만 사용하면 동시 차감 후 재고가 예상치와 달라질 수 있다")
	@Test
	void decrease_whenConcurrentWithSynchronized_resultMayDiffer() throws Exception {
		// given
		Product product = createProduct("test-product-sync", 1000);
		createStock(product, 100);
		int threadCount = 100;

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		measureConcurrent(
			"synchronized",
			threadCount,
			() -> stockConcurrencyService.decreaseWithSynchronized(product.getId(), 1),
			errors
		);

		// then
		Stock updated = stockRepository.findByProductId(product.getId()).orElseThrow();
		// synchronized만 사용하면 트랜잭션 커밋 시점이 락 밖에서 발생해 수량이 어긋날 수 있음
		assertThat(updated.getQuantity()).isGreaterThanOrEqualTo(0);
	}

	@DisplayName("synchronized와 트랜잭션을 함께 사용하면 동시 차감 후 재고가 정확히 0이 된다")
	@Test
	void decrease_whenConcurrentWithSynchronizedAndTransaction_remainZero() throws Exception {
		// given
		Product product = createProduct("test-product-sync-tx", 1000);
		createStock(product, 100);
		int threadCount = 100;

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		measureConcurrent(
			"synchronized-tx",
			threadCount,
			() -> stockConcurrencyService.decreaseWithSynchronizedAndTransaction(product.getId(), 1),
			errors
		);

		// then
		Stock updated = stockRepository.findByProductId(product.getId()).orElseThrow();
		assertThat(updated.getQuantity()).isZero();
	}

	@DisplayName("ReentrantLock과 트랜잭션을 함께 사용하면 동시 차감 후 재고가 정확히 0이 된다")
	@Test
	void decrease_whenConcurrentWithReentrantLockAndTransaction_remainZero() throws Exception {
		// given
		Product product = createProduct("test-product-reentrant-tx", 1000);
		createStock(product, 100);
		int threadCount = 100;

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		measureConcurrent(
			"reentrant-lock-tx",
			threadCount,
			() -> stockConcurrencyService.decreaseWithReentrantLockAndTransaction(product.getId(), 1),
			errors
		);

		// then
		Stock updated = stockRepository.findByProductId(product.getId()).orElseThrow();
		assertThat(updated.getQuantity()).isZero();
	}

	@DisplayName("Optimistic Lock은 재시도로 수량을 감소시키지만 항상 0을 보장하지는 않는다")
	@Test
	void decrease_whenConcurrentWithOptimisticLock_allowRemainingStock() throws Exception {
		// given
		Product product = createProduct("test-product-optimistic", 1000);
		createStock(product, 100);
		int threadCount = 100;

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		measureConcurrent(
			"optimistic-lock",
			threadCount,
			() -> stockConcurrencyService.decreaseWithOptimisticLock(product.getId(), 1),
			errors
		);

		// then
		Stock updated = stockRepository.findByProductId(product.getId()).orElseThrow();
		assertThat(updated.getQuantity()).isBetween(0, 100);
		assertThat(updated.getQuantity() - errors.size()).isZero();
	}

	@DisplayName("Pessimistic Lock을 사용하면 동시 차감 후 재고가 정확히 0이 된다")
	@Test
	void decrease_whenConcurrentWithPessimisticLock_remainZero() throws Exception {
		// given
		Product product = createProduct("test-product-pessimistic", 1000);
		createStock(product, 100);
		int threadCount = 100;

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		measureConcurrent(
			"pessimistic-lock",
			threadCount,
			() -> stockInventoryService.decrease(product.getId(), 1),
			errors
		);

		// then
		Stock updated = stockRepository.findByProductId(product.getId()).orElseThrow();
		assertThat(updated.getQuantity()).isZero();
		assertThat(errors).isEmpty();
	}

	@DisplayName("Pessimistic Lock 배치를 사용하면 동시 차감 후 재고가 정확히 0이 된다")
	@Test
	void decrease_whenConcurrentWithPessimisticLockBatch_remainZero() throws Exception {
		// given
		Product product = createProduct("test-product-pessimistic-batch", 1000);
		createStock(product, 100);
		int threadCount = 100;

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		measureConcurrent(
			"pessimistic-lock-batch",
			threadCount,
			() -> stockInventoryService.decreaseBatch(
				StockDecreaseBatchCommand.from(java.util.Map.of(product.getId(), 1))
			),
			errors
		);

		// then
		Stock updated = stockRepository.findByProductId(product.getId()).orElseThrow();
		assertThat(updated.getQuantity()).isZero();
		assertThat(errors).isEmpty();
	}

	@DisplayName("synchronized만 사용하면 동시 차감 시 낙관적 락 예외가 발생할 수 있다")
	@Test
	void decrease_whenConcurrentWithSynchronized_collectOptimisticLockErrors() throws Exception {
		// given
		Product product = createProduct("test-product-sync", 1000);
		createStock(product, 100);
		int threadCount = 100;

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		measureConcurrent(
			"1",
			threadCount,
			() -> stockConcurrencyService.decreaseWithSynchronized(product.getId(), 1),
			errors
		);

		// then
		Stock updated = stockRepository.findByProductId(product.getId()).orElseThrow();
		// synchronized만 사용하면 트랜잭션 커밋 시점이 락 밖에서 발생해 수량이 어긋날 수 있음
		assertThat(updated.getQuantity()).isGreaterThanOrEqualTo(0);
		assertThat(updated.getQuantity()).isEqualTo(errors.size());
		for (Throwable error : errors) {
			System.out.println(error);
		}
	}

	private void measureConcurrent(String label, int threadCount, Runnable task, ConcurrentLinkedQueue<Throwable> errors) throws InterruptedException {
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);

		long start = System.nanoTime();
		try {
			for (int i = 0; i < threadCount; i++) {
				executor.submit(() -> {
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

			startLatch.countDown();
			boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
			assertThat(completed).isTrue();
		} finally {
			executor.shutdown();
			boolean terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
			if (!terminated) {
				executor.shutdownNow();
			}
		}

		long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
		System.out.println("[" + label + "] duration=" + durationMs + "(ms)");
	}

	private Stock createStock(Product product, int quantity) {
		Stock stock = Stock.builder()
			.product(product)
			.quantity(quantity)
			.build();
		return stockRepository.save(stock);
	}

	private Product createProduct(String name, int price) {
		Product product = Product.builder()
			.name(name)
			.price(price)
			.status(ProductStatus.ON_SALE)
			.build();
		return productRepository.save(product);
	}
}
