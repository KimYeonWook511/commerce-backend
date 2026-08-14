package com.commerce.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.test.context.TestPropertySource;

import com.commerce.member.domain.Member;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.domain.Order;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentCloseCode;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

/**
 * 방치된 결제를 종결하는 배치를 실제 DB 위에서 확인한다. 슬롯이 실제로 반납되는지는 유일 제약 위에서만
 * 드러난다 — 그 자리가 비어야 회원이 그 주문을 다시 결제할 수 있다.
 *
 * <p>만료 임계를 0으로 두어 만들어지자마자 대상이 되게 한다. 행이 만들어진 시각은 감사 컬럼이 채우므로
 * 과거로 옮길 수 없고, 임계값을 낮추는 것이 같은 조건을 만든다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("docker")
@TestPropertySource(properties = "payment.postprocess.expire.threshold=0s")
@Import({
	PersistenceCleanupTestSupport.class,
	MemberPersistenceTestSupport.class,
	ProductPersistenceTestSupport.class,
	OrderPersistenceTestSupport.class,
	PaymentPersistenceTestSupport.class
})
class ExpirePaymentUseCaseIntegrationTest {

	private static final int UNIT_PRICE = 5_000;

	@Autowired
	private ExpirePaymentUseCase expirePaymentUseCase;

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

	@DisplayName("승인을 부르지 않은 채 방치된 결제를 종결하고 활성 슬롯을 반납한다")
	@Test
	void expire_whenPaymentWasNeverCalled_closesItAndReleasesSlot() {
		Payment payment = readyPayment();

		expirePaymentUseCase.expire();

		Payment expired = reload(payment);
		assertThat(expired.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
		// 부른 적이 없어 성립하지 않은 승인이라는 것이 없으므로 실패로 종결하지 않는다.
		assertThat(expired.getCloseCode()).isEqualTo(PaymentCloseCode.SESSION_TIMEOUT);
		assertThat(expired.getActiveOrderKey()).isNull();
	}

	@DisplayName("슬롯이 반납되어 그 주문에 새 결제가 설 수 있다")
	@Test
	void expire_whenSlotIsReleased_letsNextPaymentTakeIt() {
		Payment payment = readyPayment();
		expirePaymentUseCase.expire();

		Payment next = paymentPersistence.save(Payment.start(payment.getOrderId(), payment.getMemberId(),
			PaymentPg.NAVERPAY, "PAY-next-" + (++uniqueSuffix), "idem-next-" + uniqueSuffix, payment.getAmount()));

		assertThat(paymentPersistence.findActiveByOrderId(payment.getOrderId()))
			.get()
			.extracting(Payment::getId)
			.isEqualTo(next.getId());
	}

	@DisplayName("승인을 부른 뒤의 결제는 이 배치가 건드리지 않는다")
	@Test
	void expire_whenApprovalWasAlreadyCalled_leavesPaymentUntouched() {
		Payment payment = readyPayment();
		payment.markInProgress("pg-payment-1", LocalDateTime.now());
		paymentPersistence.save(payment);

		expirePaymentUseCase.expire();

		// 종결시켰다가 그 승인이 성공하면 돈은 나갔는데 우리 기록은 종결이다.
		Payment untouched = reload(payment);
		assertThat(untouched.getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
		assertThat(untouched.getCloseCode()).isNull();
	}

	private Payment reload(Payment payment) {
		return paymentPersistence.findById(payment.getId()).orElseThrow();
	}

	private Payment readyPayment() {
		Member member = memberPersistence.save(
			Member.createUser("expire-" + (++uniqueSuffix) + "@example.com", "password123", "expire-user"));
		Product product = productPersistence.save(
			Product.create("상품-" + (++uniqueSuffix), UNIT_PRICE, null, null, ProductStatus.ON_SALE));
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 1, UNIT_PRICE);
		Order saved = orderPersistence.saveAndFlush(order);

		return paymentPersistence.save(Payment.start(saved.getId(), member.getId(), PaymentPg.NAVERPAY,
			"PAY-" + uniqueSuffix, "idem-" + uniqueSuffix, saved.getTotalPrice()));
	}
}
