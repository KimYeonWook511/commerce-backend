package com.commerce.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.commerce.member.domain.Member;
import com.commerce.order.domain.Order;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;

import jakarta.persistence.EntityManager;

@DataJpaTest
@ActiveProfiles("test")
@Import({PaymentRepositoryAdapter.class, MemberPersistenceTestSupport.class, ProductPersistenceTestSupport.class, OrderPersistenceTestSupport.class})
class PaymentRepositoryJpaAdapterTest {

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private MemberPersistenceTestSupport memberPersistence;


	@Autowired
	private ProductPersistenceTestSupport productPersistence;

	@Autowired
	private OrderPersistenceTestSupport orderPersistence;

	@Autowired
	private EntityManager em;

	@DisplayName("merchantPayKey로 결제 정보를 조회한다")
	@Test
	void findByMerchantPayKey_whenPaymentExists_returnPayment() {
		// given
		Member member = memberPersistence.save(createMember());
		Product product = productPersistence.save(createProduct("product-PAY-FIXTURE", 1000));
		Order order = orderPersistence.saveAndFlush(createOrder(member, product, "PAY-FIXTURE"));
		Payment payment = Payment.createCompleted(
			order,
			PaymentProvider.NAVERPAY,
			"PAY-1",
			"pg-payment-id-1",
			LocalDateTime.of(2026, 3, 5, 19, 30)
		);
		paymentRepository.save(payment);
		em.flush();
		em.clear();

		// when
		Optional<Payment> result = paymentRepository.findByMerchantPayKey("PAY-1");

		// then
		assertThat(result).isPresent();
		assertThat(result.get().getMerchantPayKey()).isEqualTo("PAY-1");
		assertThat(result.get().getPgPaymentId()).isEqualTo("pg-payment-id-1");
	}

	@DisplayName("merchantPayKey에 해당하는 결제가 없으면 빈 값을 반환한다")
	@Test
	void findByMerchantPayKey_whenPaymentNotExists_returnEmpty() {
		// when
		Optional<Payment> result = paymentRepository.findByMerchantPayKey("PAY-NOT-FOUND");

		// then
		assertThat(result).isEmpty();
	}

	private Member createMember() {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		return Member.builder()
			.email("payment-repo-" + suffix + "@example.com")
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
}
