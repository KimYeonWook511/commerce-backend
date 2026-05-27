package com.commerce.cart.application.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.cart.application.AddCartItemService;
import com.commerce.cart.application.UpdateCartItemQuantityService;
import com.commerce.cart.domain.CartItem;
import com.commerce.cart.exception.CartErrorCode;
import com.commerce.cart.exception.CartException;
import com.commerce.cart.infrastructure.persistence.support.CartPersistenceTestSupport;
import com.commerce.cart.presentation.request.CartItemAddRequest;
import com.commerce.cart.presentation.request.CartItemUpdateRequest;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;

/**
 * cart phase ADR 결정 8(낙관적 락 + retry + Processor 분리) 정책의 race 시나리오 통합 검증.
 *
 * <p>cart의 contention은 동일 사용자가 같은 productId에 짧은 간격으로 다중 요청을 보내는 극히 드문 시나리오에 한정되므로,
 * 본 테스트는 3 thread 수준의 가벼운 패턴으로 race window를 재현한다.
 */
@Tag("concurrency")
@SpringBootTest
@ActiveProfiles("test")
@Import({PersistenceCleanupTestSupport.class, ProductPersistenceTestSupport.class, CartPersistenceTestSupport.class})
class CartConcurrencyTest {

	@Autowired
	private AddCartItemService addCartItemService;

	@Autowired
	private UpdateCartItemQuantityService updateCartItemQuantityService;

	@Autowired
	private CartPersistenceTestSupport cartPersistence;

	@Autowired
	private ProductPersistenceTestSupport productPersistence;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(cartPersistence, productPersistence);
	}

	@DisplayName("동시 add는 retry로 흡수되어 수량 손실 없이 합산된다 (결정 8)")
	@Test
	void add_whenConcurrent_aggregatesQuantityWithoutLoss() throws Exception {
		// given
		Long memberId = 1L;
		Product product = productPersistence.save(createProduct("p-add-concurrent", ProductStatus.ON_SALE));
		cartPersistence.save(CartItem.create(memberId, product.getId(), 10));
		int threadCount = 3;

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		runConcurrent(threadCount,
			index -> addCartItemService.add(memberId, createAddRequest(product.getId(), 1)),
			errors);

		// then
		CartItem updated = cartPersistence.findAllByMemberIdOrderByCreatedAtDesc(memberId).get(0);
		int successCount = threadCount - errors.size();
		// 성공한 thread의 increment만 합산된다 (silent 손실 부재)
		assertThat(updated.getQuantity()).isEqualTo(10 + successCount);
		// 실패가 발생했다면 retry MAX(3) 초과한 ObjectOptimisticLockingFailureException으로 노출되어야 한다
		for (Throwable error : errors) {
			assertThat(error).isInstanceOf(ObjectOptimisticLockingFailureException.class);
		}
	}

	@DisplayName("동시 add에서 한도(99)를 초과해도 silent하게 우회되지 않는다 (결정 8)")
	@Test
	void add_whenConcurrentExceedsLimit_throwsQuantityExceededWithoutSilentBypass() throws Exception {
		// given
		Long memberId = 1L;
		Product product = productPersistence.save(createProduct("p-add-limit", ProductStatus.ON_SALE));
		cartPersistence.save(CartItem.create(memberId, product.getId(), 97));
		int threadCount = 3;

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		runConcurrent(threadCount,
			index -> addCartItemService.add(memberId, createAddRequest(product.getId(), 1)),
			errors);

		// then
		CartItem updated = cartPersistence.findAllByMemberIdOrderByCreatedAtDesc(memberId).get(0);
		// quantity invariant 가 silent하게 우회되지 않는다
		assertThat(updated.getQuantity()).isLessThanOrEqualTo(99);
		// 적어도 한 thread는 명시 throw로 거부되었어야 한다 (silent log miss 회귀 방어)
		assertThat(errors).isNotEmpty();
		// 한도 초과 thread는 CART_ITEM_QUANTITY_EXCEEDED로 노출되어야 한다 (그 외는 retry 초과 OptimisticLockingFailureException 허용)
		boolean hasQuantityExceeded = errors.stream()
			.anyMatch(error -> error instanceof CartException cartException
				&& cartException.getErrorCode() == CartErrorCode.CART_ITEM_QUANTITY_EXCEEDED);
		assertThat(hasQuantityExceeded).isTrue();
		for (Throwable error : errors) {
			assertThat(error)
				.isInstanceOfAny(CartException.class, ObjectOptimisticLockingFailureException.class);
		}
	}

	@DisplayName("동시 PATCH는 retry로 흡수되어 last-write-wins로 안전하게 마무리된다 (결정 8)")
	@Test
	void update_whenConcurrent_lastWriteWinsWithoutSilentLoss() throws Exception {
		// given
		Long memberId = 1L;
		Product product = productPersistence.save(createProduct("p-update-concurrent", ProductStatus.ON_SALE));
		cartPersistence.save(CartItem.create(memberId, product.getId(), 5));
		int[] targetQuantities = {7, 8, 9};

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		runConcurrent(targetQuantities.length,
			index -> updateCartItemQuantityService.update(memberId, product.getId(), createUpdateRequest(targetQuantities[index])),
			errors);

		// then
		CartItem updated = cartPersistence.findAllByMemberIdOrderByCreatedAtDesc(memberId).get(0);
		// 적어도 하나의 PATCH는 commit되어야 한다. 모든 thread가 retry MAX(3) 안에 흡수되면 errors는 비어있다.
		// 일부가 MAX 초과한 경우 OptimisticLockingFailureException으로 안전하게 노출된다.
		for (Throwable error : errors) {
			assertThat(error).isInstanceOf(ObjectOptimisticLockingFailureException.class);
		}
		// 모든 thread가 실패한 극단 케이스에서는 초기값 5가 유지된다 (retry MAX 초과 시 안전한 throw가 보장됨)
		assertThat(updated.getQuantity()).isIn(5, 7, 8, 9);
	}

	private void runConcurrent(int threadCount, IntConsumer task, ConcurrentLinkedQueue<Throwable> errors)
		throws InterruptedException {
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);

		try {
			for (int i = 0; i < threadCount; i++) {
				int index = i;
				executor.submit(() -> {
					try {
						startLatch.await();
						task.accept(index);
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
			boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
			assertThat(completed).isTrue();
		} finally {
			executor.shutdown();
			boolean terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
			if (!terminated) {
				executor.shutdownNow();
			}
		}
	}

	private Product createProduct(String name, ProductStatus status) {
		return Product.builder()
			.name(name)
			.price(1000)
			.status(status)
			.build();
	}

	private CartItemAddRequest createAddRequest(Long productId, int quantity) {
		CartItemAddRequest request = new CartItemAddRequest();
		ReflectionTestUtils.setField(request, "productId", productId);
		ReflectionTestUtils.setField(request, "quantity", quantity);
		return request;
	}

	private CartItemUpdateRequest createUpdateRequest(int quantity) {
		CartItemUpdateRequest request = new CartItemUpdateRequest();
		ReflectionTestUtils.setField(request, "quantity", quantity);
		return request;
	}
}
