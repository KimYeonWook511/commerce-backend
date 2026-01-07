package com.commerce.stock.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.product.domain.Product;
import com.commerce.product.repository.ProductRepository;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.repository.StockRepository;

@SpringBootTest
@ActiveProfiles("test")
class StockConcurrencyTest {

	@Autowired
	private StockService stockService;

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
		Stock stock = createStock(product, 100);

		int threadCount = 100;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch readyLatch = new CountDownLatch(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);

		// when
		for (int i = 0; i < threadCount; i++) {
			executor.submit(() -> {
				readyLatch.countDown();
				try {
					startLatch.await();
					stockService.decrease(product.getId(), 1);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					doneLatch.countDown();
				}
			});
		}

		readyLatch.await();
		startLatch.countDown();
		boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
		assertThat(completed).isTrue();
		executor.shutdown();

		// then
		Stock updated = stockRepository.findByProductId(product.getId()).orElseThrow();
		assertThat(updated.getQuantity()).isGreaterThan(0);
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
			.build();
		return productRepository.save(product);
	}
}
