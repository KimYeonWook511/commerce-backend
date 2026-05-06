package com.commerce.payment.integration;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.commerce.member.domain.Member;
import com.commerce.member.repository.MemberRepository;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.infrastructure.JpaOrderRepository;
import com.commerce.orderitem.repository.OrderItemRepository;
import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentAttemptStatus;
import com.commerce.payment.domain.PaymentAttemptType;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.application.PaymentApprovalService;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.integration.support.PaymentPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.JpaProductRepository;

@SpringBootTest
@ActiveProfiles("test")
@Import(PaymentPersistenceTestSupport.class)
class PaymentApprovalServiceIntegrationTest {

	@Autowired
	private PaymentApprovalService paymentApprovalService;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private JpaProductRepository productRepository;

	@Autowired
	private JpaOrderRepository orderRepository;

	@Autowired
	private OrderItemRepository orderItemRepository;

	@MockitoSpyBean
	private PaymentRepository paymentRepository;

	@Autowired
	private PaymentPersistenceTestSupport paymentPersistence;

	@AfterEach
	void tearDown() {
		paymentPersistence.deleteAllInBatch();
		orderItemRepository.deleteAllInBatch();
		orderRepository.deleteAllInBatch();
		productRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
	}

	@DisplayName("payment 저장이 실패하면 approve attempt는 REQUESTED로 남고 order는 INIT를 유지한다")
	@Test
	void completeApprovedPayment_whenPersistingPaymentFails_keepApproveAttemptRequestedAndOrderInit() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-ROLLBACK-1", 1000);
		paymentPersistence.saveApproveAttempt("PAY-ROLLBACK-1", "pg-rollback-1", 1000, PaymentProvider.NAVERPAY);
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
		assertThat(orderRepository.findByMerchantPayKey("PAY-ROLLBACK-1").orElseThrow().getStatus())
			.isEqualTo(OrderStatus.INIT);
		assertThat(getAttempt("PAY-ROLLBACK-1", "pg-rollback-1", PaymentAttemptType.APPROVE).getStatus())
			.isEqualTo(PaymentAttemptStatus.REQUESTED);
	}

	private Member createMember() {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		return memberRepository.save(
			Member.builder()
				.email("payment-rollback-" + suffix + "@example.com")
				.password("password123")
				.username("u" + suffix)
				.build()
		);
	}

	private Order createOrder(Member member, String merchantPayKey, int totalPrice) {
		Product product = productRepository.save(
			Product.builder()
				.name("product-" + merchantPayKey)
				.price(totalPrice)
				.status(ProductStatus.ON_SALE)
				.build()
		);

		Order order = Order.create(member);
		order.addOrderItem(product, 1);
		order.assignMerchantPayKey(merchantPayKey);
		return orderRepository.saveAndFlush(order);
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
