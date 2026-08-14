package com.commerce.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

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
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.payment.application.dto.StartPaymentCommand;
import com.commerce.payment.application.dto.StartPaymentResult;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentCloseCode;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

/**
 * 결제 시작 경로를 실제 DB와 실제 선점 저장소 위에서 확인한다. 활성 슬롯 유일 제약과 멱등키 유일 제약이
 * 이 흐름의 뼈대라 대역으로는 거동이 재현되지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("docker")
@Import({
	PersistenceCleanupTestSupport.class,
	MemberPersistenceTestSupport.class,
	ProductPersistenceTestSupport.class,
	OrderPersistenceTestSupport.class,
	PaymentPersistenceTestSupport.class
})
class StartPaymentUseCaseIntegrationTest {

	private static final int UNIT_PRICE = 5_000;

	@Autowired
	private StartPaymentUseCase startPaymentUseCase;

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

	private static int uniqueSuffix = 0;

	@DynamicPropertySource
	static void registerContainers(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
		TestcontainersSupport.registerRedis(registry);
	}

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(
			paymentPersistence, memberPersistence, productPersistence, orderPersistence
		);
	}

	@DisplayName("결제를 시작하면 결제창을 띄우기 전에 결제 행이 먼저 생기고 활성 슬롯을 잡는다")
	@Test
	void start_whenOrderIsPayable_createsReadyPaymentHoldingActiveSlot() {
		Member member = saveMember();
		Order order = saveOrder(member, 2);

		StartPaymentResult result = startPaymentUseCase.start(command(member, order, "idem-1"));

		Payment payment = paymentPersistence.findByPaymentKey(result.merchantPayKey()).orElseThrow();
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
		assertThat(payment.getActiveOrderKey()).isEqualTo(order.getId());
		assertThat(payment.getMemberId()).isEqualTo(member.getId());
		assertThat(payment.getAmount()).isEqualTo(order.getTotalPrice());
		assertThat(payment.getIdempotencyKey()).isEqualTo("idem-1");
		assertThat(payment.getPgPaymentId()).isNull();
	}

	@DisplayName("결제 시작 응답에는 결제창을 여는 값이 그대로 담긴다")
	@Test
	void start_whenOrderIsPayable_returnsCheckoutValues() {
		Member member = saveMember();
		Order order = saveOrder(member, 2);

		StartPaymentResult result = startPaymentUseCase.start(command(member, order, "idem-1"));

		assertThat(result.clientId()).isEqualTo("test");
		assertThat(result.chainId()).isEqualTo("test");
		assertThat(result.merchantPayKey()).isNotBlank();
		assertThat(result.productName()).isNotBlank();
		assertThat(result.productCount()).isEqualTo(2);
		assertThat(result.totalPayAmount()).isEqualTo(order.getTotalPrice());
		assertThat(result.taxScopeAmount()).isEqualTo(order.getTotalPrice());
		assertThat(result.taxExScopeAmount()).isZero();
		assertThat(result.returnUrl()).isEqualTo("https://test?merchantPayKey=" + result.merchantPayKey());
	}

	@DisplayName("결제를 다시 요청하면 앞 행이 자리를 내주고 새 행과 새 키가 생긴다")
	@Test
	void start_whenRequestedAgain_supersedesPreviousAndIssuesNewKey() {
		Member member = saveMember();
		Order order = saveOrder(member, 1);

		StartPaymentResult first = startPaymentUseCase.start(command(member, order, "idem-1"));
		StartPaymentResult second = startPaymentUseCase.start(command(member, order, "idem-2"));

		assertThat(second.merchantPayKey()).isNotEqualTo(first.merchantPayKey());

		Payment previous = paymentPersistence.findByPaymentKey(first.merchantPayKey()).orElseThrow();
		assertThat(previous.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
		assertThat(previous.getCloseCode()).isEqualTo(PaymentCloseCode.SUPERSEDED);
		assertThat(previous.getActiveOrderKey()).isNull();

		Payment current = paymentPersistence.findByPaymentKey(second.merchantPayKey()).orElseThrow();
		assertThat(current.getStatus()).isEqualTo(PaymentStatus.READY);
		assertThat(current.getActiveOrderKey()).isEqualTo(order.getId());
	}

	@DisplayName("남의 주문으로는 결제를 시작할 수 없고 결제 행도 생기지 않는다")
	@Test
	void start_whenOrderBelongsToAnotherMember_rejectsWithoutCreatingPayment() {
		Member owner = saveMember();
		Member stranger = saveMember();
		Order order = saveOrder(owner, 1);

		assertThatThrownBy(() -> startPaymentUseCase.start(command(stranger, order, "idem-1")))
			.isInstanceOf(OrderException.class)
			.hasMessage(OrderErrorCode.ORDER_NOT_FOUND.getMessage());

		assertThat(paymentPersistence.count()).isZero();
	}

	@DisplayName("앞 결제가 실패로 끝났으면 다음 시도가 슬롯을 잡는다")
	@Test
	void start_whenPreviousPaymentFailed_letsNextAttemptTakeSlot() {
		Member member = saveMember();
		Order order = saveOrder(member, 1);
		Payment failed = Payment.start(order.getId(), member.getId(), PaymentPg.NAVERPAY,
			"PK-failed", "idem-failed", order.getTotalPrice());
		failed.markInProgress("pg-payment-1", LocalDateTime.now());
		failed.fail(PaymentCloseCode.PG_DECLINED, "거절");
		paymentPersistence.save(failed);

		StartPaymentResult result = startPaymentUseCase.start(command(member, order, "idem-1"));

		Payment current = paymentPersistence.findByPaymentKey(result.merchantPayKey()).orElseThrow();
		assertThat(current.getStatus()).isEqualTo(PaymentStatus.READY);
		assertThat(current.getActiveOrderKey()).isEqualTo(order.getId());
	}

	@DisplayName("승인 결과를 모르는 결제가 걸린 주문에는 새 결제를 시작할 수 없다")
	@Test
	void start_whenApprovalResultUnknown_rejectsWithoutCreatingPayment() {
		Member member = saveMember();
		Order order = saveOrder(member, 1);
		Payment unknown = Payment.start(order.getId(), member.getId(), PaymentPg.NAVERPAY,
			"PK-unknown", "idem-unknown", order.getTotalPrice());
		unknown.markInProgress("pg-payment-1", LocalDateTime.now());
		unknown.markUnknown();
		paymentPersistence.save(unknown);

		assertThatThrownBy(() -> startPaymentUseCase.start(command(member, order, "idem-1")))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_RESULT_PENDING.getMessage());

		assertThat(paymentPersistence.count()).isEqualTo(1);
		assertThat(paymentPersistence.findActiveByOrderId(order.getId()).orElseThrow().getStatus())
			.isEqualTo(PaymentStatus.UNKNOWN);
	}

	@DisplayName("같은 멱등키로 다시 요청하면 앞서 만든 결제가 그대로 돌아오고 시도가 하나다")
	@Test
	void start_whenSameIdempotencyKeyRepeated_returnsPreviousPayment() {
		Member member = saveMember();
		Order order = saveOrder(member, 1);

		StartPaymentResult first = startPaymentUseCase.start(command(member, order, "idem-1"));
		StartPaymentResult second = startPaymentUseCase.start(command(member, order, "idem-1"));

		assertThat(second.merchantPayKey()).isEqualTo(first.merchantPayKey());
		assertThat(paymentPersistence.count()).isEqualTo(1);
	}

	@DisplayName("다른 회원이 같은 멱등키를 보내도 앞 회원의 결제가 돌아오지 않는다")
	@Test
	void start_whenAnotherMemberSendsSameKey_doesNotReturnOthersPayment() {
		Member first = saveMember();
		Member second = saveMember();
		Order firstOrder = saveOrder(first, 1);
		Order secondOrder = saveOrder(second, 1);

		StartPaymentResult firstResult = startPaymentUseCase.start(command(first, firstOrder, "shared-key"));
		StartPaymentResult secondResult = startPaymentUseCase.start(command(second, secondOrder, "shared-key"));

		assertThat(secondResult.merchantPayKey()).isNotEqualTo(firstResult.merchantPayKey());
		Payment secondPayment = paymentPersistence.findByPaymentKey(secondResult.merchantPayKey()).orElseThrow();
		assertThat(secondPayment.getMemberId()).isEqualTo(second.getId());
		assertThat(secondPayment.getOrderId()).isEqualTo(secondOrder.getId());
	}

	@DisplayName("같은 멱등키에 다른 주문을 실어 보내면 앞 주문의 결제창 값을 돌려주지 않고 거절한다")
	@Test
	void start_whenSameKeyCarriesAnotherOrder_rejects() {
		Member member = saveMember();
		Order first = saveOrder(member, 1);
		Order second = saveOrder(member, 1);

		startPaymentUseCase.start(command(member, first, "idem-1"));

		assertThatThrownBy(() -> startPaymentUseCase.start(command(member, second, "idem-1")))
			.isInstanceOf(PaymentException.class)
			.hasMessage(PaymentErrorCode.PAYMENT_IDEMPOTENCY_KEY_CONFLICT.getMessage());

		assertThat(paymentPersistence.count()).isEqualTo(1);
	}

	private StartPaymentCommand command(Member member, Order order, String idempotencyKey) {
		return new StartPaymentCommand(member.getId(), order.getId(), PaymentPg.NAVERPAY, idempotencyKey);
	}

	private Member saveMember() {
		return memberPersistence.save(
			Member.createUser("start-payment-" + (++uniqueSuffix) + "@example.com", "password123", "start-user"));
	}

	private Order saveOrder(Member member, int quantity) {
		Product product = productPersistence.save(
			Product.create("상품-" + (++uniqueSuffix), UNIT_PRICE, null, null, ProductStatus.ON_SALE));
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), quantity, UNIT_PRICE);
		return orderPersistence.saveAndFlush(order);
	}
}
