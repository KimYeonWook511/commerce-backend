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
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.payment.legacy.domain.Payment;
import com.commerce.payment.legacy.domain.PaymentProvider;
import com.commerce.payment.legacy.domain.PaymentReservation;
import com.commerce.payment.legacy.domain.PaymentType;
import com.commerce.payment.legacy.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.legacy.infrastructure.persistence.support.PaymentReservationPersistenceTestSupport;
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
 * PAID 주문 취소가 환불 의도·주문 상태 전이·재고 복구를 한 트랜잭션으로 커밋하는지 실제 DB로 검증한다.
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
	PaymentReservationPersistenceTestSupport.class,
	StockPersistenceTestSupport.class
})
class CancelPaidOrderServiceIntegrationTest {

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
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
	private PaymentReservationPersistenceTestSupport reservationPersistence;

	@Autowired
	private StockPersistenceTestSupport stockPersistence;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(
			paymentPersistence, reservationPersistence, memberPersistence,
			productPersistence, orderPersistence, stockPersistence
		);
	}

	@DisplayName("PAID 주문 취소 중 재고 복구가 실패하면 환불 의도가 남지 않는다")
	@Test
	void cancelPaidOrder_whenStockRestoreFails_rollsBackCancelPayment() {
		// given: 재고 행을 만들지 않아, 환불 의도 영속화 이후 단계인 재고 복구에서 실패하게 한다
		String merchantPayKey = "PAY-CANCEL-TX-ROLLBACK";
		Member member = memberPersistence.save(createMember("rollback"));
		Product product = productPersistence.save(createProduct("cancel-tx-rollback"));
		Order order = orderPersistence.saveAndFlush(createPaidOrder(member, product));
		saveApproveSucceeded(order, member, merchantPayKey, "pg-cancel-tx-rollback");

		// when & then: 재고 복구 단계까지 도달했음을 에러 코드로 확인한다
		assertThatThrownBy(() -> cancelPaidOrderService.cancelPaidOrder(member.getId(), order.getId()))
			.isInstanceOf(StockException.class)
			.satisfies(ex -> assertThat(((StockException) ex).getErrorCode())
				.isEqualTo(StockErrorCode.STOCK_NOT_FOUND));

		// then: 환불 의도가 커밋되지 않아 대사가 집어갈 고아 취소 결제가 남지 않는다
		assertThat(paymentPersistence.countCancelPayments(merchantPayKey)).isZero();
		assertThat(orderPersistence.getOrderStatusById(order.getId())).isEqualTo(OrderStatus.PAID);
	}

	@DisplayName("PAID 주문 취소가 성공하면 환불 의도·주문 취소·재고 복구가 함께 커밋된다")
	@Test
	void cancelPaidOrder_whenStockExists_commitsCancelPaymentAndOrderCancel() {
		// given
		String merchantPayKey = "PAY-CANCEL-TX-COMMIT";
		Member member = memberPersistence.save(createMember("commit"));
		Product product = productPersistence.save(createProduct("cancel-tx-commit"));
		Order order = orderPersistence.saveAndFlush(createPaidOrder(member, product));
		saveApproveSucceeded(order, member, merchantPayKey, "pg-cancel-tx-commit");
		stockPersistence.save(Stock.create(product.getId(), 0));

		// when
		cancelPaidOrderService.cancelPaidOrder(member.getId(), order.getId());

		// then
		assertThat(paymentPersistence.countCancelPayments(merchantPayKey)).isEqualTo(1L);
		assertThat(orderPersistence.getOrderStatusById(order.getId())).isEqualTo(OrderStatus.CANCELED);
		assertThat(stockPersistence.findByProductId(product.getId()).orElseThrow().getQuantity())
			.isEqualTo(ORDER_QUANTITY);
	}

	// ── 헬퍼 ──

	private static final int PRODUCT_PRICE = 1000;
	private static final int ORDER_QUANTITY = 1;

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

	/**
	 * 취소 대상이 되려면 APPROVE 결제가 SUCCEEDED여야 한다.
	 * succeed()를 save() 전에 호출한다 — save()는 자기 트랜잭션에서 커밋하고 detached 엔티티를 돌려주므로,
	 * 순서를 바꾸면 REQUESTED로 남아 미확정 승인 가드에 먼저 걸린다.
	 */
	private void saveApproveSucceeded(Order order, Member member, String merchantPayKey, String pgPaymentId) {
		PaymentReservation reservation = reservationPersistence.save(
			PaymentReservation.createReserved(
				order.getId(), member.getId(), PRODUCT_PRICE * ORDER_QUANTITY, PaymentProvider.NAVERPAY,
				merchantPayKey, LocalDateTime.now().plusMinutes(15)));

		Payment approve = Payment.createRequested(reservation, PaymentType.APPROVE, pgPaymentId);
		approve.succeed(LocalDateTime.now());
		paymentPersistence.save(approve);
	}
}
