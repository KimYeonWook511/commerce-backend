package com.commerce.payment.legacy.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.commerce.member.domain.Member;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.payment.legacy.application.port.NotificationPort;
import com.commerce.payment.legacy.domain.Payment;
import com.commerce.payment.legacy.domain.PaymentProvider;
import com.commerce.payment.legacy.domain.PaymentReservation;
import com.commerce.payment.legacy.domain.PaymentStatus;
import com.commerce.payment.legacy.domain.PaymentType;
import com.commerce.payment.legacy.domain.repository.PaymentRepository;
import com.commerce.payment.legacy.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.legacy.infrastructure.persistence.support.PaymentReservationPersistenceTestSupport;
import com.commerce.payment.legacy.naverpay.application.port.NaverPayGateway;
import com.commerce.payment.legacy.naverpay.application.port.result.NaverPayCancelResult;
import com.commerce.payment.legacy.naverpay.application.port.result.NaverPayHistoryResult;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

@Tag("docker")
@SpringBootTest
@ActiveProfiles("test")
@Import({
	PersistenceCleanupTestSupport.class,
	MemberPersistenceTestSupport.class,
	ProductPersistenceTestSupport.class,
	OrderPersistenceTestSupport.class,
	PaymentPersistenceTestSupport.class,
	PaymentReservationPersistenceTestSupport.class
})
class PaymentReconciliationIntegrationTest {

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
	}

	@Autowired
	private ReconcilePaymentUseCase reconciliationUseCase;

	@Autowired
	private PaymentRepository paymentRepository;

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
	private PersistenceCleanupTestSupport persistenceCleanup;

	@MockitoBean
	private NaverPayGateway naverPayGateway;

	@MockitoBean
	private NotificationPort notificationPort;

	@AfterEach
	void tearDown() {
		Mockito.reset(naverPayGateway, notificationPort);
		persistenceCleanup.deleteAllInBatch(
			paymentPersistence, reservationPersistence, memberPersistence, productPersistence, orderPersistence
		);
	}

	/**
	 * 테스트 2: UNKNOWN 결제 대사 정상 흐름.
	 * succeedApproval 의 findByIdForUpdate order 락 경로가 실제 MySQL에서 동작함을 포함한다.
	 */
	@DisplayName("UNKNOWN 결제를 대사할 때 PG 승인이 확인되면 payment가 SUCCEEDED, order가 PAID, 차단 가드가 해제된다")
	@Test
	void reconcile_unknownPaymentWithPgApproved_succeedsPaymentAndPaysOrder() {
		// given: INIT order + APPROVE UNKNOWN payment
		Member member = memberPersistence.save(createMember("r2"));
		Product product = productPersistence.save(createProduct("prod-r2", 1000));
		Order order = orderPersistence.saveAndFlush(createOrder(member, product));

		PaymentReservation reservation = reservationPersistence.save(
			PaymentReservation.createReserved(
				order.getId(), member.getId(), 1000, PaymentProvider.NAVERPAY,
				"PAY-RECN-2", LocalDateTime.now().plusMinutes(15)));

		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-recn-2");
		payment.markUnknown("timeout", LocalDateTime.now().minusMinutes(2));
		paymentPersistence.save(payment);

		given(naverPayGateway.getApprovalHistory("pg-recn-2"))
			.willReturn(NaverPayHistoryResult.approved("PAY-RECN-2", 1000));

		// when
		reconciliationUseCase.reconcile();

		// then: payment SUCCEEDED
		assertThat(paymentPersistence.findApproveSucceeded("PAY-RECN-2")).isPresent();

		// then: order PAID
		assertThat(orderPersistence.getOrderStatusById(order.getId())).isEqualTo(OrderStatus.PAID);

		// then: 차단 가드 해제 (UNKNOWN approve payment 없음)
		assertThat(paymentRepository.existsUnknownByOrderId(order.getId())).isFalse();
	}

	/**
	 * 테스트 3: C — 만료-취소-후-지연-승인 정합성 (#222 검증 기준).
	 * CANCELED 주문의 UNKNOWN 결제가 PG에서 승인 확인될 때 보상 취소 + 통지가 수행되고,
	 * 2회 호출 후에도 이중 환불(중복 cancel)이 발생하지 않음을 확인한다.
	 */
	@DisplayName("CANCELED 주문의 UNKNOWN 결제가 PG 승인이면 보상 취소·통지가 수행되고 2회 호출해도 이중 환불이 없다")
	@Test
	void reconcile_canceledOrderWithPgApproved_compensatesAndIdempotent() {
		// given: INIT order → CANCELED + APPROVE UNKNOWN payment
		Member member = memberPersistence.save(createMember("r3"));
		Product product = productPersistence.save(createProduct("prod-r3", 1000));
		Order order = orderPersistence.saveAndFlush(createOrder(member, product));

		PaymentReservation reservation = reservationPersistence.save(
			PaymentReservation.createReserved(
				order.getId(), member.getId(), 1000, PaymentProvider.NAVERPAY,
				"PAY-RECN-3", LocalDateTime.now().plusMinutes(15)));

		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-recn-3");
		payment.markUnknown("timeout", LocalDateTime.now().minusMinutes(2));
		paymentPersistence.save(payment);

		// 주문을 CANCELED 상태로 전환 (A 원천 차단이 뚫린 시나리오)
		Order savedOrder = orderPersistence.findById(order.getId()).orElseThrow();
		savedOrder.cancel();
		orderPersistence.save(savedOrder);

		given(naverPayGateway.getApprovalHistory("pg-recn-3"))
			.willReturn(NaverPayHistoryResult.approved("PAY-RECN-3", 1000));
		given(naverPayGateway.cancel(eq("pg-recn-3"), eq(1000), any(String.class)))
			.willReturn(NaverPayCancelResult.success());

		// when: 1회 reconcile
		reconciliationUseCase.reconcile();

		// then: approve payment → FAILED (보상 취소로 인한 ORDER_CANCELED — 보상된 APPROVE 는 새 상태 없이 FAILED 로 유지한다)
		Payment approvePayment = paymentPersistence.getPayment(
			"PAY-RECN-3", PaymentProvider.NAVERPAY, "pg-recn-3", PaymentType.APPROVE);
		assertThat(approvePayment.getStatus()).isEqualTo(PaymentStatus.FAILED);

		// then: PG 보상 취소 cancel payment → SUCCEEDED
		Payment cancelPayment = paymentPersistence.getPayment(
			"PAY-RECN-3", PaymentProvider.NAVERPAY, "pg-recn-3", PaymentType.CANCEL);
		assertThat(cancelPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

		// then: order CANCELED 유지 (돈은 환불됐고 주문도 취소 — 정합성 보장)
		assertThat(orderPersistence.getOrderStatusById(order.getId())).isEqualTo(OrderStatus.CANCELED);

		// then: 통지 호출 발생
		then(notificationPort).should().notifyManualReviewRequired(
			eq(order.getId()), eq("PAY-RECN-3"), any(String.class));

		// when: 2회 reconcile (멱등성 검증)
		reconciliationUseCase.reconcile();

		// then: cancel payment 추가 생성 없음 (이중 환불 없음)
		assertThat(paymentPersistence.countCancelPayments("PAY-RECN-3")).isEqualTo(1L);

		// then: 통지도 1회만 발생 (2회 호출해도 추가 통지 없음)
		then(notificationPort).should(times(1)).notifyManualReviewRequired(any(), any(), any());
	}

	private Member createMember(String suffix) {
		return Member.createUser("recn-int-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 6) + "@example.com", "password123", "u-recn-" + suffix);
	}

	private Product createProduct(String name, int price) {
		return Product.create(name, price, null, null, ProductStatus.ON_SALE);
	}

	private Order createOrder(Member member, Product product) {
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 1, product.getPrice());
		return order;
	}
}
