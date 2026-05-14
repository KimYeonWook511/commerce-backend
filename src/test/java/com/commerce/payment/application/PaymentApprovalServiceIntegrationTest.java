package com.commerce.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.doThrow;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.commerce.member.domain.Member;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.payment.application.PaymentApprovalService;
import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentAttemptStatus;
import com.commerce.payment.domain.PaymentAttemptType;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import support.PersistenceCleanupTestSupport;

@SpringBootTest
@ActiveProfiles("test")
@Import({PersistenceCleanupTestSupport.class, PaymentPersistenceTestSupport.class, MemberPersistenceTestSupport.class, ProductPersistenceTestSupport.class, OrderPersistenceTestSupport.class})
class PaymentApprovalServiceIntegrationTest {

	@Autowired
	private PaymentApprovalService paymentApprovalService;

	@Autowired
	private MemberPersistenceTestSupport memberPersistence;

	@Autowired
	private ProductPersistenceTestSupport productPersistence;

	@Autowired
	private OrderPersistenceTestSupport orderPersistence;

	@MockitoSpyBean
	private PaymentRepository paymentRepository;

	@Autowired
	private PaymentPersistenceTestSupport paymentPersistence;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(
			paymentPersistence, memberPersistence, productPersistence, orderPersistence
		);
	}

	@DisplayName("payment 저장이 실패하면 approve attempt는 REQUESTED로 남고 order는 INIT를 유지한다")
	@Test
	void completeApprovedPayment_whenPersistingPaymentFails_keepApproveAttemptRequestedAndOrderInit() {
		// given
		Member member = memberPersistence.save(createMember());
		Product product = productPersistence.save(createProduct("product-PAY-ROLLBACK-1", 1000));
		orderPersistence.saveAndFlush(createOrder(member, product, "PAY-ROLLBACK-1"));
		paymentPersistence.save(createApproveAttempt("PAY-ROLLBACK-1", "pg-rollback-1", 1000));
		doThrow(new DataIntegrityViolationException("duplicate key"))
			.when(paymentRepository)
			.save(any());

		// when & then
		assertThatThrownBy(() -> paymentApprovalService.completeApprovedPayment(
			"PAY-ROLLBACK-1",
			PaymentProvider.NAVERPAY,
			"pg-rollback-1",
			LocalDateTime.now()
		))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_DUPLICATE));

		assertThat(paymentPersistence.findPaymentByMerchantPayKey("PAY-ROLLBACK-1")).isEmpty();
		assertThat(orderPersistence.getOrderStatusByMerchantPayKey("PAY-ROLLBACK-1"))
			.isEqualTo(OrderStatus.INIT);
		assertThat(getAttempt("PAY-ROLLBACK-1", "pg-rollback-1", PaymentAttemptType.APPROVE).getStatus())
			.isEqualTo(PaymentAttemptStatus.REQUESTED);
	}

	private Member createMember() {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		return Member.builder()
			.email("payment-rollback-" + suffix + "@example.com")
			.password("password123")
			.username("u" + suffix)
			.build();
	}

	private Product createProduct(String name, int price) {
		return Product.builder()
			.name(name)
			.price(price)
			.status(ProductStatus.ON_SALE)
			.build();
	}

	private Order createOrder(Member member, Product product, String merchantPayKey) {
		Order order = Order.create(member);
		order.addOrderItem(product, 1);
		order.assignMerchantPayKey(merchantPayKey);
		return order;
	}

	private PaymentAttempt createApproveAttempt(String merchantPayKey, String paymentId, int totalPayAmount) {
		return PaymentAttempt.createApproveRequested(
			merchantPayKey,
			paymentId,
			totalPayAmount,
			PaymentProvider.NAVERPAY
		);
	}

	private PaymentAttempt getAttempt(String merchantPayKey, String paymentId, PaymentAttemptType type) {
		return paymentPersistence.getAttempt(
			merchantPayKey,
			PaymentProvider.NAVERPAY,
			paymentId,
			type
		);
	}
}
