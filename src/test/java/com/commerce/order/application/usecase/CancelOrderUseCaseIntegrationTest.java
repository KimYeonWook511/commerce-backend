package com.commerce.order.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.commerce.member.domain.Member;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.application.dto.OrderCancelRefundStatus;
import com.commerce.order.application.dto.OrderCancelResult;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.payment.application.port.PaymentGatewayPort;
import com.commerce.payment.application.port.dto.PgCallRecord;
import com.commerce.payment.application.port.dto.PgRefundResult;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundReason;
import com.commerce.payment.domain.RefundRequester;
import com.commerce.payment.domain.RefundReviewCode;
import com.commerce.payment.domain.RefundStatus;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.PgCallLogPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.RefundPersistenceTestSupport;
import com.commerce.payment.domain.PgErrorType;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.infrastructure.persistence.support.StockPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

/**
 * 주문 취소가 환불 사건을 만들어 결제사로 잇는 흐름을 실제 DB·실제 선점 저장소 위에서 확인한다.
 * 멱등 흡수와 응답 값 접기는 커밋 경계를 지나야 드러나므로 대역으로 대체할 수 없다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("docker")
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
class CancelOrderUseCaseIntegrationTest {

	private static final int UNIT_PRICE = 10_000;

	@Autowired
	private CancelOrderUseCase cancelOrderUseCase;

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

	@DisplayName("결제된 주문을 취소하면 우리가 발급한 사건 키를 가진 환불이 생기고 응답에 금액 둘이 담긴다")
	@Test
	void cancel_whenPaidOrder_opensRefundAndAnswersAmounts() {
		Fixture fixture = paidOrder();
		givenGatewaySucceeds();

		OrderCancelResult result = cancelOrderUseCase.cancel(
			fixture.memberId(), fixture.orderId(), "cancel-key-1");

		assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELED);
		assertThat(result.getRefundStatus()).isEqualTo(OrderCancelRefundStatus.COMPLETED);
		assertThat(result.getRefundedAmount()).isEqualTo(UNIT_PRICE);
		assertThat(result.getRemainingAmount()).isZero();

		assertThat(refundPersistence.findAll()).hasSize(1);
		Refund refund = refundPersistence.findAll().get(0);
		assertThat(refund.getRefundKey()).isNotBlank();
		assertThat(refund.getRequester()).isEqualTo(RefundRequester.MEMBER);
		assertThat(refund.getReason()).isEqualTo(RefundReason.ORDER_CANCELED);
		assertThat(refund.getIdempotencyKey()).isEqualTo("cancel-key-1");
	}

	@DisplayName("주문 취소를 두 번 불러도 환불이 하나이고 결제사에 다시 나가지 않는다")
	@Test
	void cancel_whenCalledTwice_keepsSingleRefundAndDoesNotResend() {
		Fixture fixture = paidOrder();
		givenGatewaySucceeds();

		OrderCancelResult first = cancelOrderUseCase.cancel(
			fixture.memberId(), fixture.orderId(), "cancel-key-2");
		OrderCancelResult second = cancelOrderUseCase.cancel(
			fixture.memberId(), fixture.orderId(), "cancel-key-2");

		// 같은 요청 키의 재요청은 앞선 결과를 그대로 돌려준다. 응답이 유실되어 회원이 다시 보낸 경우다.
		assertThat(second.getStatus()).isEqualTo(OrderStatus.CANCELED);
		assertThat(second.getRefundStatus()).isEqualTo(first.getRefundStatus());
		assertThat(second.getRefundedAmount()).isEqualTo(first.getRefundedAmount());
		assertThat(second.getRemainingAmount()).isEqualTo(first.getRemainingAmount());

		assertThat(refundPersistence.findAll()).hasSize(1);
		then(paymentGatewayPort).should().refund(any(), any(), any());
	}

	@DisplayName("남의 주문 번호로는 취소할 수 없고 환불도 생기지 않는다")
	@Test
	void cancel_whenOrderBelongsToAnotherMember_rejects() {
		Fixture fixture = paidOrder();
		Member other = memberPersistence.save(member("other"));

		assertThatThrownBy(() ->
			cancelOrderUseCase.cancel(other.getId(), fixture.orderId(), "cancel-key-3"))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));

		assertThat(refundPersistence.findAll()).isEmpty();
		then(paymentGatewayPort).should(never()).refund(any(), any(), any());
	}

	@DisplayName("결제 결과를 모르는 결제가 걸린 주문은 취소할 수 없고 환불도 생기지 않는다")
	@Test
	void cancel_whenUnknownPaymentExists_rejects() {
		int suffix = ++uniqueSuffix;
		Member member = memberPersistence.save(member("unknown" + suffix));
		Product product = productPersistence.save(product());
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 1, UNIT_PRICE);
		order.completePayment();
		Order savedOrder = orderPersistence.saveAndFlush(order);
		stockPersistence.save(Stock.create(product.getId(), 0));

		// 결과를 모르는 결제가 활성 슬롯을 쥐고 있어 그 주문에는 다른 결제가 설 수 없다.
		Payment unknown = Payment.start(savedOrder.getId(), member.getId(), PaymentPg.NAVERPAY,
			"PK-unknown-" + suffix, "idem-unknown-" + suffix, UNIT_PRICE);
		unknown.markInProgress("pg-unknown-" + suffix, LocalDateTime.now());
		unknown.markUnknown();
		paymentPersistence.save(unknown);

		assertThatThrownBy(() ->
			cancelOrderUseCase.cancel(member.getId(), savedOrder.getId(), "cancel-key-4"))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_REFUND_NOT_AVAILABLE));

		assertThat(refundPersistence.findAll()).isEmpty();
		assertThat(orderPersistence.getOrderStatusById(savedOrder.getId())).isEqualTo(OrderStatus.PAID);
	}

	@DisplayName("결제사를 부르지 못한 채 끝나도 취소는 성공이고 환불 진행 상태만 처리 중으로 나간다")
	@Test
	void cancel_whenGatewayDoesNotAnswer_answersInProgress() {
		Fixture fixture = paidOrder();
		given(paymentGatewayPort.refund(any(), any(), any()))
			.willReturn(PgRefundResult.unanswered("응답 없음",
				new PgCallRecord(PgErrorType.TIMEOUT, null, null, null)));

		OrderCancelResult result = cancelOrderUseCase.cancel(
			fixture.memberId(), fixture.orderId(), "cancel-key-5");

		assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELED);
		assertThat(result.getRefundStatus()).isEqualTo(OrderCancelRefundStatus.IN_PROGRESS);
		assertThat(refundPersistence.findAll().get(0).getStatus()).isEqualTo(RefundStatus.UNKNOWN);
	}

	@DisplayName("이 요청 안에서 사람이 처리해야 하는 상태가 되어도 회원에게는 처리 중으로 나간다")
	@Test
	void cancel_whenRefundNeedsManualReview_answersInProgress() {
		Fixture fixture = paidOrder();
		given(paymentGatewayPort.refund(any(), any(), any()))
			.willReturn(PgRefundResult.terminalFailure(RefundReviewCode.CANCEL_DEADLINE_EXPIRED,
				"취소 기한 만료", new PgCallRecord(PgErrorType.NONE, "CancelDeadlineExpired", 200, "{}")));

		OrderCancelResult result = cancelOrderUseCase.cancel(
			fixture.memberId(), fixture.orderId(), "cancel-key-6");

		assertThat(result.getRefundStatus()).isEqualTo(OrderCancelRefundStatus.IN_PROGRESS);
		assertThat(refundPersistence.findAll().get(0).getStatus()).isEqualTo(RefundStatus.MANUAL_REVIEW);
	}

	@DisplayName("회원이 보낸 멱등키가 승인 반려 환불의 자리를 차지하지 않는다")
	@Test
	void cancel_whenMemberKeyLooksLikeSystemKey_bothRefundsCanExist() {
		Fixture fixture = paidOrder();
		givenGatewaySucceeds();

		// 시스템 환불이 요청 키 자리에 담는 값과 같은 문자열을 회원이 보내도 공간이 갈려 있다.
		cancelOrderUseCase.cancel(fixture.memberId(), fixture.orderId(),
			RefundReason.ORDER_NOT_PAYABLE.name());

		Payment payment = paymentPersistence.findById(fixture.paymentId()).orElseThrow();
		Refund systemRefund = Refund.open(payment.getId(), "RF-system-" + uniqueSuffix,
			RefundRequester.SYSTEM, RefundReason.ORDER_NOT_PAYABLE.name(), 1, RefundReason.ORDER_NOT_PAYABLE);
		refundPersistence.save(systemRefund);

		assertThat(refundPersistence.findAll()).hasSize(2);
	}

	@DisplayName("결제 전 주문 취소는 환불이 없고 금액 둘이 모두 0이다")
	@Test
	void cancel_whenOrderNotPaid_answersWithoutRefund() {
		Member member = memberPersistence.save(member("init"));
		Product product = productPersistence.save(product());
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 1, UNIT_PRICE);
		Order saved = orderPersistence.saveAndFlush(order);
		stockPersistence.save(Stock.create(product.getId(), 0));

		OrderCancelResult result = cancelOrderUseCase.cancel(member.getId(), saved.getId(), "cancel-key-7");

		assertThat(result.getRefundStatus()).isEqualTo(OrderCancelRefundStatus.NONE);
		assertThat(result.getRefundedAmount()).isZero();
		assertThat(result.getRemainingAmount()).isZero();
		assertThat(refundPersistence.findAll()).isEmpty();
	}

	// ── 헬퍼 ──

	private void givenGatewaySucceeds() {
		given(paymentGatewayPort.refund(any(), any(), any()))
			.willReturn(PgRefundResult.succeeded("pg-cancel-tx-1", "성공",
				new PgCallRecord(PgErrorType.NONE, "Success", 200, "{}")));
	}

	private Fixture paidOrder() {
		int suffix = ++uniqueSuffix;
		Member member = memberPersistence.save(member("paid" + suffix));
		Product product = productPersistence.save(product());
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 1, UNIT_PRICE);
		order.completePayment();
		Order savedOrder = orderPersistence.saveAndFlush(order);
		stockPersistence.save(Stock.create(product.getId(), 0));

		Payment payment = Payment.start(savedOrder.getId(), member.getId(), PaymentPg.NAVERPAY,
			"PK-" + suffix, "idem-" + suffix, UNIT_PRICE);
		payment.markInProgress("pg-payment-" + suffix, LocalDateTime.now());
		payment.succeed(UNIT_PRICE, "pg-tx-" + suffix);
		Payment savedPayment = paymentPersistence.save(payment);

		return new Fixture(member.getId(), savedOrder.getId(), savedPayment.getId());
	}

	private Member member(String tag) {
		return Member.createUser(
			"cancel-uc-" + tag + "-" + UUID.randomUUID().toString().substring(0, 6) + "@example.com",
			"password123", "u-" + UUID.randomUUID().toString().substring(0, 5));
	}

	private Product product() {
		return Product.create("상품-" + UUID.randomUUID().toString().substring(0, 6),
			UNIT_PRICE, null, null, ProductStatus.ON_SALE);
	}

	private record Fixture(Long memberId, Long orderId, Long paymentId) {
	}
}
