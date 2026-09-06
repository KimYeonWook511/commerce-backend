package com.commerce.order.application.usecase.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.commerce.member.domain.Member;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.application.dto.OrderCreateCommand;
import com.commerce.order.application.dto.OrderCreateItem;
import com.commerce.order.application.usecase.CancelOrderUseCase;
import com.commerce.order.application.usecase.CreateOrderUseCase;
import com.commerce.order.domain.Order;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.payment.application.port.PaymentGatewayPort;
import com.commerce.payment.application.port.dto.PgCallRecord;
import com.commerce.payment.application.port.dto.PgRefundResult;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.PgErrorType;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.PgCallLogPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.RefundPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.infrastructure.persistence.support.StockPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

/**
 * 결제완료 주문 취소가 재고를 복구하는 순서와 주문 생성이 재고를 차감하는 순서가 둘 다 상품 식별자
 * 오름차순이라는 계약을 실제 DB 위에서 확인한다. 두 흐름이 같은 재고 행을 반대 순서로 겨냥하게 준비해
 * 두어야 그 계약이 깨졌을 때 교착이 재현될 무대가 생긴다.
 *
 * <p>어느 요청이 먼저 끝나는지는 단언하지 않는다. 지켜야 하는 것은 둘 다 오류 없이 끝나고 재고가 두
 * 작업을 모두 반영한 값이 되는 것이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("docker")
@Tag("concurrency")
@Import({
	PersistenceCleanupTestSupport.class,
	MemberPersistenceTestSupport.class,
	ProductPersistenceTestSupport.class,
	OrderPersistenceTestSupport.class,
	PaymentPersistenceTestSupport.class,
	RefundPersistenceTestSupport.class,
	PgCallLogPersistenceTestSupport.class,
	StockPersistenceTestSupport.class
})
class CancelOrderUseCaseCreateOrderConcurrencyTest {

	private static final int UNIT_PRICE_1 = 10_000;
	private static final int UNIT_PRICE_2 = 20_000;
	private static final int CANCELED_ORDER_QUANTITY_1 = 2;
	private static final int CANCELED_ORDER_QUANTITY_2 = 3;
	private static final int NEW_ORDER_QUANTITY_1 = 1;
	private static final int NEW_ORDER_QUANTITY_2 = 1;
	private static final int INITIAL_STOCK = 10;

	@Autowired
	private CancelOrderUseCase cancelOrderUseCase;

	@Autowired
	private CreateOrderUseCase createOrderUseCase;

	@MockitoBean
	private PaymentGatewayPort paymentGatewayPort;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@Autowired
	private MemberPersistenceTestSupport memberPersistence;

	@Autowired
	private ProductPersistenceTestSupport productPersistence;

	@Autowired
	private OrderPersistenceTestSupport orderPersistence;

	@Autowired
	private PaymentPersistenceTestSupport paymentPersistence;

	@Autowired
	private RefundPersistenceTestSupport refundPersistence;

	@Autowired
	private PgCallLogPersistenceTestSupport pgCallLogPersistence;

	@Autowired
	private StockPersistenceTestSupport stockPersistence;

	private static int uniqueSuffix = 0;

	@DynamicPropertySource
	static void registerContainers(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
		TestcontainersSupport.registerRedis(registry);
	}

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(
			pgCallLogPersistence, refundPersistence, paymentPersistence,
			memberPersistence, productPersistence, orderPersistence, stockPersistence
		);
	}

	@DisplayName("결제완료 주문 취소와 주문 생성이 재고 행을 반대 순서로 겨냥해도 데드락 없이 끝나고 재고가 두 작업을 모두 반영한다")
	@Test
	void cancelAndCreate_whenStockRowsTargetedInOppositeOrder_finishesWithoutDeadlock() throws InterruptedException {
		// given
		given(paymentGatewayPort.refund(any(), any(), any()))
			.willReturn(PgRefundResult.succeeded("pg-cancel-tx", "성공",
				new PgCallRecord(PgErrorType.NONE, "Success", 200, "{}")));

		int suffix = ++uniqueSuffix;
		Member member = memberPersistence.save(Member.createUser(
			"deadlock-race-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 6) + "@example.com",
			"password123", "u-" + UUID.randomUUID().toString().substring(0, 5)));
		Product product1 = productPersistence.save(product(UNIT_PRICE_1));
		Product product2 = productPersistence.save(product(UNIT_PRICE_2));
		stockPersistence.save(Stock.create(product1.getId(), INITIAL_STOCK));
		stockPersistence.save(Stock.create(product2.getId(), INITIAL_STOCK));

		// 결제완료 주문은 두 상품을 오름차순으로 담는다.
		Order order = Order.create(member.getId());
		order.addOrderItem(product1.getId(), CANCELED_ORDER_QUANTITY_1, UNIT_PRICE_1);
		order.addOrderItem(product2.getId(), CANCELED_ORDER_QUANTITY_2, UNIT_PRICE_2);
		order.completePayment();
		Order savedOrder = orderPersistence.saveAndFlush(order);

		int totalPrice = savedOrder.getTotalPrice();
		Payment payment = Payment.start(savedOrder.getId(), member.getId(), PaymentPg.NAVERPAY,
			"PK-deadlock-" + suffix, "idem-deadlock-" + suffix, totalPrice);
		payment.markInProgress("pg-payment-deadlock-" + suffix, LocalDateTime.now());
		payment.succeed(totalPrice, "pg-tx-deadlock-" + suffix);
		paymentPersistence.save(payment);

		// 주문 생성 요청은 같은 두 상품을 반대 순서로 담는다.
		OrderCreateCommand createCommand = OrderCreateCommand.builder()
			.memberId(member.getId())
			.idempotencyKey("create-deadlock-" + suffix)
			.items(List.of(
				OrderCreateItem.builder().productId(product2.getId()).quantity(NEW_ORDER_QUANTITY_2).build(),
				OrderCreateItem.builder().productId(product1.getId()).quantity(NEW_ORDER_QUANTITY_1).build()
			))
			.build();

		// when
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		executor.submit(() -> {
			ready.countDown();
			try {
				start.await();
				cancelOrderUseCase.cancel(member.getId(), savedOrder.getId(), "cancel-deadlock-" + suffix, List.of());
			} catch (Throwable t) {
				errors.add(t);
			} finally {
				done.countDown();
			}
		});
		executor.submit(() -> {
			ready.countDown();
			try {
				start.await();
				createOrderUseCase.createOrder(createCommand);
			} catch (Throwable t) {
				errors.add(t);
			} finally {
				done.countDown();
			}
		});

		ready.await();
		start.countDown();
		boolean completed = done.await(30, java.util.concurrent.TimeUnit.SECONDS);
		executor.shutdown();

		// then
		assertThat(completed).isTrue();
		assertThat(errors).isEmpty();
		assertThat(orderPersistence.count()).isEqualTo(2L);
		assertThat(stockPersistence.findByProductId(product1.getId()).orElseThrow().getQuantity())
			.isEqualTo(INITIAL_STOCK + CANCELED_ORDER_QUANTITY_1 - NEW_ORDER_QUANTITY_1);
		assertThat(stockPersistence.findByProductId(product2.getId()).orElseThrow().getQuantity())
			.isEqualTo(INITIAL_STOCK + CANCELED_ORDER_QUANTITY_2 - NEW_ORDER_QUANTITY_2);
	}

	private Product product(int price) {
		return Product.create("상품-" + UUID.randomUUID().toString().substring(0, 6),
			price, null, null, ProductStatus.ON_SALE);
	}
}
