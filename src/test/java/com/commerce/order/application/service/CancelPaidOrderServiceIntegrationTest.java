package com.commerce.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.UUID;

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

import com.commerce.member.domain.Member;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.application.service.CancelPaidOrderService.CancelPaidOrderResult;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.RefundReason;
import com.commerce.payment.domain.RefundRequester;
import com.commerce.payment.domain.RefundStatus;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.RefundPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.domain.exception.StockErrorCode;
import com.commerce.stock.domain.exception.StockException;
import com.commerce.stock.infrastructure.persistence.support.StockPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

/**
 * 결제된 주문 취소가 환불 의도·주문 상태 전이·재고 복구를 한 트랜잭션으로 커밋하는지 실제 DB로 검증한다.
 * 롤백 검증은 트랜잭션 경계가 실제로 작동해야 드러나므로 단위 테스트로 대체할 수 없다.
 */
@Tag("docker")
@SpringBootTest
@ActiveProfiles("test")
@Import({
	PersistenceCleanupTestSupport.class,
	MemberPersistenceTestSupport.class,
	ProductPersistenceTestSupport.class,
	OrderPersistenceTestSupport.class,
	PaymentPersistenceTestSupport.class,
	RefundPersistenceTestSupport.class,
	StockPersistenceTestSupport.class
})
class CancelPaidOrderServiceIntegrationTest {

	private static final int PRODUCT_PRICE = 1000;
	private static final int ORDER_QUANTITY = 1;
	private static final String IDEMPOTENCY_KEY = "cancel-tx-key";

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
		TestcontainersSupport.registerRedis(registry);
	}

	@Autowired
	private CancelPaidOrderService cancelPaidOrderService;

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
	private StockPersistenceTestSupport stockPersistence;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(
			refundPersistence, paymentPersistence, memberPersistence,
			productPersistence, orderPersistence, stockPersistence
		);
	}

	@DisplayName("주문 취소가 롤백되면 환불 의도도 남지 않는다")
	@Test
	void cancelPaidOrder_whenStockRestoreFails_rollsBackRefund() {
		// given: 재고 행을 만들지 않아, 환불 의도를 연 다음 단계인 재고 복구에서 실패하게 한다
		Member member = memberPersistence.save(createMember("rollback"));
		Product product = productPersistence.save(createProduct("cancel-tx-rollback"));
		Order order = orderPersistence.saveAndFlush(createPaidOrder(member, product));
		saveSucceededPayment(order, member, "PK-CANCEL-TX-ROLLBACK", "pg-cancel-tx-rollback");

		// when & then: 재고 복구 단계까지 도달했음을 에러 코드로 확인한다
		assertThatThrownBy(() ->
			cancelPaidOrderService.cancelPaidOrder(member.getId(), order.getId(), IDEMPOTENCY_KEY))
			.isInstanceOf(StockException.class)
			.satisfies(ex -> assertThat(((StockException) ex).getErrorCode())
				.isEqualTo(StockErrorCode.STOCK_NOT_FOUND));

		// then: 대사가 집어갈 고아 환불이 남지 않고 주문도 결제완료 그대로다
		assertThat(refundPersistence.findAll()).isEmpty();
		assertThat(orderPersistence.getOrderStatusById(order.getId())).isEqualTo(OrderStatus.PAID);
	}

	@DisplayName("주문 취소가 성공하면 환불 의도·주문 취소·재고 복구가 함께 커밋된다")
	@Test
	void cancelPaidOrder_whenStockExists_commitsRefundAndOrderCancel() {
		Member member = memberPersistence.save(createMember("commit"));
		Product product = productPersistence.save(createProduct("cancel-tx-commit"));
		Order order = orderPersistence.saveAndFlush(createPaidOrder(member, product));
		Payment payment = saveSucceededPayment(order, member, "PK-CANCEL-TX-COMMIT", "pg-cancel-tx-commit");
		stockPersistence.save(Stock.create(product.getId(), 0));

		CancelPaidOrderResult result =
			cancelPaidOrderService.cancelPaidOrder(member.getId(), order.getId(), IDEMPOTENCY_KEY);

		assertThat(refundPersistence.findAll()).hasSize(1);
		assertThat(result.refund().getStatus()).isEqualTo(RefundStatus.REQUESTED);
		assertThat(result.refund().getRequester()).isEqualTo(RefundRequester.MEMBER);
		assertThat(result.refund().getReason()).isEqualTo(RefundReason.ORDER_CANCELED);
		assertThat(result.refund().getAmount()).isEqualTo(PRODUCT_PRICE * ORDER_QUANTITY);
		assertThat(result.remainingAmount()).isZero();

		assertThat(orderPersistence.getOrderStatusById(order.getId())).isEqualTo(OrderStatus.CANCELED);
		assertThat(stockPersistence.findByProductId(product.getId()).orElseThrow().getQuantity())
			.isEqualTo(ORDER_QUANTITY);
		assertThat(paymentPersistence.findById(payment.getId()).orElseThrow().getTotalRefundedAmount())
			.isEqualTo(PRODUCT_PRICE * ORDER_QUANTITY);
	}

	@DisplayName("같은 요청 키로 취소를 두 번 불러도 환불 사건이 하나다")
	@Test
	void cancelPaidOrder_whenCalledTwiceWithSameKey_keepsSingleRefund() {
		Member member = memberPersistence.save(createMember("idem"));
		Product product = productPersistence.save(createProduct("cancel-tx-idem"));
		Order order = orderPersistence.saveAndFlush(createPaidOrder(member, product));
		Payment payment = saveSucceededPayment(order, member, "PK-CANCEL-TX-IDEM", "pg-cancel-tx-idem");
		stockPersistence.save(Stock.create(product.getId(), 0));

		cancelPaidOrderService.cancelPaidOrder(member.getId(), order.getId(), IDEMPOTENCY_KEY);
		// 두 번째 요청은 이미 취소된 주문이라 취소 자체가 거부된다 — 환불이 늘지 않는 것이 요점이다.
		assertThatThrownBy(() ->
			cancelPaidOrderService.cancelPaidOrder(member.getId(), order.getId(), IDEMPOTENCY_KEY))
			.isInstanceOf(RuntimeException.class);

		assertThat(refundPersistence.findAll()).hasSize(1);
		assertThat(paymentPersistence.findById(payment.getId()).orElseThrow().getTotalRefundedAmount())
			.isEqualTo(PRODUCT_PRICE * ORDER_QUANTITY);
	}

	// ── 헬퍼 ──

	private Member createMember(String suffix) {
		return Member.createUser(
			"cancel-tx-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 6) + "@example.com",
			"password123",
			"u-cxtx-" + suffix.substring(0, Math.min(suffix.length(), 5))
		);
	}

	private Product createProduct(String name) {
		return Product.create(name, PRODUCT_PRICE, null, null, ProductStatus.ON_SALE);
	}

	private Order createPaidOrder(Member member, Product product) {
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), ORDER_QUANTITY, product.getPrice());
		order.completePayment();
		return order;
	}

	/** 취소 대상이 되려면 결제가 성공으로 확정돼 승인 금액을 들고 있어야 한다 */
	private Payment saveSucceededPayment(Order order, Member member, String paymentKey, String pgPaymentId) {
		Payment payment = Payment.start(order.getId(), member.getId(), PaymentPg.NAVERPAY,
			paymentKey, "idem-" + paymentKey, PRODUCT_PRICE * ORDER_QUANTITY);
		payment.markInProgress(pgPaymentId, LocalDateTime.now());
		payment.succeed(PRODUCT_PRICE * ORDER_QUANTITY, "pg-tx-" + paymentKey);
		return paymentPersistence.save(payment);
	}
}
