package com.commerce.order.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.commerce.member.domain.Member;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.application.dto.OrderCreateCommand;
import com.commerce.order.application.dto.OrderCreateItem;
import com.commerce.order.application.port.OrderIdempotencyStore;
import com.commerce.order.application.dto.OrderCreateResult;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.order.infrastructure.OrderIdempotencyStoreUnavailableException;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.infrastructure.persistence.support.StockPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

@SpringBootTest
@ActiveProfiles("test")
@Tag("docker")
@Import({PersistenceCleanupTestSupport.class, MemberPersistenceTestSupport.class, ProductPersistenceTestSupport.class, StockPersistenceTestSupport.class, OrderPersistenceTestSupport.class})
class OrderCreateUseCaseIdempotencyTest {

	@Autowired
	private OrderCreateUseCase orderCreateUseCase;

	@MockitoSpyBean
	private OrderIdempotencyStore orderIdempotencyStore;

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

	@DynamicPropertySource
	static void registerContainers(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
		TestcontainersSupport.registerRedis(registry);
	}

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(
			memberPersistence, productPersistence, stockPersistence, orderPersistence
		);
	}

	@DisplayName("같은 멱등키로 재요청하면 주문이 중복 생성되지 않는다")
	@Test
	void createOrder_whenSameIdempotencyKey_returnSameOrder() {
		// given
		Member member = memberPersistence.save(createMember("order-idempotency"));
		Product product = productPersistence.save(createProduct("product-1", 1000));
		stockPersistence.save(createStock(product, 10));

		OrderCreateCommand command = OrderCreateCommand.builder()
			.memberId(member.getId())
			.idempotencyKey("idem-key")
			.items(List.of(OrderCreateItem.builder().productId(product.getId()).quantity(2).build()))
			.build();

		// when
		OrderCreateResult first = orderCreateUseCase.createOrder(command);
		OrderCreateResult second = orderCreateUseCase.createOrder(command);

		// then
		assertThat(first.getOrderId()).isEqualTo(second.getOrderId());
		assertThat(orderPersistence.count()).isEqualTo(1);
		assertThat(orderPersistence.countItems()).isEqualTo(1);
		assertThat(stockPersistence.findByProductId(product.getId()).orElseThrow().getQuantity())
			.isEqualTo(8);
	}

	@DisplayName("Redis TTL 만료 후 재요청하면 중복 주문 없이 기존 주문을 반환한다")
	@Test
	void createOrder_whenIdempotencyKeyExpired_returnExistingOrder() {
		// given
		Member member = memberPersistence.save(createMember("order-ttl-expired"));
		Product product = productPersistence.save(createProduct("product-ttl", 1000));
		stockPersistence.save(createStock(product, 10));

		OrderCreateCommand command = OrderCreateCommand.builder()
			.memberId(member.getId())
			.idempotencyKey("ttl-expired-key")
			.items(List.of(OrderCreateItem.builder().productId(product.getId()).quantity(2).build()))
			.build();

		// when
		OrderCreateResult first = orderCreateUseCase.createOrder(command);

		// Redis TTL 만료 시뮬레이션: Redis 상태를 직접 제거한다
		orderIdempotencyStore.clear(member.getId(), "ttl-expired-key");

		// TTL 만료 후 동일 키로 재요청 → DB 사전 find 로 기존 주문 발견 → 기존 주문 반환
		OrderCreateResult second = orderCreateUseCase.createOrder(command);

		// then
		assertThat(first.getOrderId()).isEqualTo(second.getOrderId());
		assertThat(orderPersistence.count()).isEqualTo(1);
		assertThat(stockPersistence.findByProductId(product.getId()).orElseThrow().getQuantity())
			.isEqualTo(8);
	}

	@DisplayName("처리 중인 멱등키로 재요청하면 ORDER_IDEMPOTENCY_IN_PROGRESS 예외를 던진다")
	@Test
	void createOrder_whenKeyAlreadyReserved_throwInProgress() {
		// given
		Member member = memberPersistence.save(createMember("order-in-progress"));
		String idempotencyKey = "in-progress-key";

		// 같은 키를 미리 reserve 해 처리 중 상태를 시뮬레이션한다
		orderIdempotencyStore.reserve(member.getId(), idempotencyKey, Duration.ofSeconds(60));

		OrderCreateCommand command = OrderCreateCommand.builder()
			.memberId(member.getId())
			.idempotencyKey(idempotencyKey)
			.items(List.of(OrderCreateItem.builder().productId(1L).quantity(1).build()))
			.build();

		// when & then
		assertThatThrownBy(() -> orderCreateUseCase.createOrder(command))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException)ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_IDEMPOTENCY_IN_PROGRESS));

		// cleanup
		orderIdempotencyStore.clear(member.getId(), idempotencyKey);
	}

	@DisplayName("동시에 같은 멱등키로 요청하면 한 요청만 주문을 생성하고 다른 요청은 409를 반환한다")
	@Test
	void createOrder_whenConcurrentSameIdempotencyKey_oneSucceedsAndOtherReturns409() throws Exception {
		// given
		Member member = memberPersistence.save(createMember("order-concurrent"));
		Product product = productPersistence.save(createProduct("product-concurrent", 1000));
		stockPersistence.save(createStock(product, 10));

		OrderCreateCommand command = OrderCreateCommand.builder()
			.memberId(member.getId())
			.idempotencyKey("concurrent-key")
			.items(List.of(OrderCreateItem.builder().productId(product.getId()).quantity(2).build()))
			.build();

		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		// when
		Future<OrderCreateResult> future1 = executor.submit(() -> {
			barrier.await();
			return orderCreateUseCase.createOrder(command);
		});
		Future<OrderCreateResult> future2 = executor.submit(() -> {
			barrier.await();
			return orderCreateUseCase.createOrder(command);
		});

		OrderCreateResult winnerResult = null;
		Throwable raceError = null;
		try {
			winnerResult = future1.get();
		} catch (ExecutionException ex) {
			raceError = ex.getCause();
		}
		try {
			OrderCreateResult other = future2.get();
			if (winnerResult == null) {
				winnerResult = other;
			} else {
				// 두 요청 모두 성공한 경우 같은 주문을 반환해야 한다.
				assertThat(other.getOrderId()).isEqualTo(winnerResult.getOrderId());
			}
		} catch (ExecutionException ex) {
			raceError = ex.getCause();
		}

		executor.shutdown();

		// then
		// 적어도 한 요청은 성공해 주문을 생성한다.
		assertThat(winnerResult).isNotNull();
		// unique 제약으로 주문은 정확히 1건만 저장되고 재고도 한 번만 차감된다.
		assertThat(orderPersistence.count()).isEqualTo(1);
		assertThat(stockPersistence.findByProductId(product.getId()).orElseThrow().getQuantity())
			.isEqualTo(8);
		// 동시 처리 충돌 시 ORDER_IDEMPOTENCY_IN_PROGRESS 예외가 발생한다.
		if (raceError != null) {
			assertThat(raceError).isInstanceOf(OrderException.class);
			assertThat(((OrderException)raceError).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_IDEMPOTENCY_IN_PROGRESS);
		}
	}

	@DisplayName("Redis reserve 가 OrderIdempotencyStoreUnavailableException 을 던지면 DB fallback 경로로 주문이 정상 생성된다")
	@Test
	void createOrder_whenStoreUnavailable_fallbackToDbAndSucceed() {
		// given
		Member member = memberPersistence.save(createMember("order-store-unavailable"));
		Product product = productPersistence.save(createProduct("product-fallback", 1000));
		stockPersistence.save(createStock(product, 10));

		OrderCreateCommand command = OrderCreateCommand.builder()
			.memberId(member.getId())
			.idempotencyKey("fallback-key")
			.items(List.of(OrderCreateItem.builder().productId(product.getId()).quantity(2).build()))
			.build();

		// Redis 장애 시뮬레이션: reserve 호출 시 도메인 예외를 던지도록 stub 한다.
		willThrow(new OrderIdempotencyStoreUnavailableException(new QueryTimeoutException("redis down")))
			.given(orderIdempotencyStore).reserve(anyLong(), anyString(), any(Duration.class));

		// when
		OrderCreateResult result = orderCreateUseCase.createOrder(command);

		// then
		// fallback 경로로 DB unique 안전망을 거쳐 주문이 정상 생성되고 재고도 차감된다.
		assertThat(result.getOrderId()).isNotNull();
		assertThat(orderPersistence.count()).isEqualTo(1);
		assertThat(stockPersistence.findByProductId(product.getId()).orElseThrow().getQuantity())
			.isEqualTo(8);
		// fallback 경로는 marker 미생성이므로 clear 호출되지 않아야 한다.
		verify(orderIdempotencyStore, never()).clear(anyLong(), anyString());
	}

	private Member createMember(String emailPrefix) {
		return Member.builder()
			.email(emailPrefix + "@example.com")
			.password("password123")
			.username("uorder")
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
}
