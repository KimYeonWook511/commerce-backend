package com.commerce.order.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
import com.commerce.order.application.port.OrderIdempotencyStore;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderCancelLine;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.payment.application.port.PaymentGatewayPort;
import com.commerce.payment.application.port.dto.PgCallRecord;
import com.commerce.payment.application.port.dto.PgRefundResult;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentCloseCode;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.Refund;
import com.commerce.payment.domain.RefundReason;
import com.commerce.payment.domain.RefundRequester;
import com.commerce.payment.domain.RefundReviewCode;
import com.commerce.payment.domain.RefundStatus;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
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
	private static final int APPLE_UNIT_PRICE = 10_000;
	private static final int PEAR_UNIT_PRICE = 20_000;
	private static final int APPLE_QUANTITY = 3;

	@Autowired
	private CancelOrderUseCase cancelOrderUseCase;

	@Autowired
	private OrderIdempotencyStore orderIdempotencyStore;

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
			fixture.memberId(), fixture.orderId(), "cancel-key-1", List.of());

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

	@DisplayName("주문 취소를 두 번 불러도 환불이 하나이고 재고도 결제사 호출도 늘지 않는다")
	@Test
	void cancel_whenCalledTwice_keepsSingleRefundAndDoesNotResend() {
		Fixture fixture = paidOrder();
		givenGatewaySucceeds();

		OrderCancelResult first = cancelOrderUseCase.cancel(
			fixture.memberId(), fixture.orderId(), "cancel-key-2", List.of());
		OrderCancelResult second = cancelOrderUseCase.cancel(
			fixture.memberId(), fixture.orderId(), "cancel-key-2", List.of());

		// 같은 요청 키의 재요청은 앞선 결과를 그대로 돌려준다. 응답이 유실되어 회원이 다시 보낸 경우다.
		assertThat(second.getStatus()).isEqualTo(OrderStatus.CANCELED);
		assertThat(second.getRefundStatus()).isEqualTo(first.getRefundStatus());
		assertThat(second.getRefundedAmount()).isEqualTo(first.getRefundedAmount());
		assertThat(second.getRemainingAmount()).isEqualTo(first.getRemainingAmount());

		assertThat(refundPersistence.findAll()).hasSize(1);
		// 재고 복구가 한 번 더 타면 없는 재고가 생긴다. 재생 판정이 지키는 것이 바로 이 값이다.
		assertThat(stockPersistence.findByProductId(fixture.productId()).orElseThrow().getQuantity())
			.isEqualTo(1);
		then(paymentGatewayPort).should().refund(any(), any(), any());
	}

	@DisplayName("취소로 종착한 주문에 새 요청 키로 다시 오면 거절되고 아무 상태도 바뀌지 않는다")
	@Test
	void cancel_whenTerminalOrderGetsNewKey_rejects() {
		Fixture fixture = paidOrder();
		givenGatewaySucceeds();
		cancelOrderUseCase.cancel(fixture.memberId(), fixture.orderId(), "cancel-key-8", List.of());

		assertThatThrownBy(() ->
			cancelOrderUseCase.cancel(fixture.memberId(), fixture.orderId(), "another-cancel-key", List.of()))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED));

		assertThat(refundPersistence.findAll()).hasSize(1);
		assertThat(stockPersistence.findByProductId(fixture.productId()).orElseThrow().getQuantity())
			.isEqualTo(1);
		then(paymentGatewayPort).should().refund(any(), any(), any());
	}

	@DisplayName("한 회원의 두 주문에 같은 요청 키를 써도 각자 취소되고 결과가 섞이지 않는다")
	@Test
	void cancel_whenSameKeyUsedOnTwoOrders_keepsThemIndependent() {
		Fixture first = paidOrder();
		Fixture second = paidOrderFor(first.memberId());
		givenGatewaySucceeds();

		OrderCancelResult firstResult = cancelOrderUseCase.cancel(
			first.memberId(), first.orderId(), "shared-cancel-key", List.of());
		OrderCancelResult secondResult = cancelOrderUseCase.cancel(
			second.memberId(), second.orderId(), "shared-cancel-key", List.of());

		assertThat(firstResult.getOrderId()).isEqualTo(first.orderId());
		assertThat(secondResult.getOrderId()).isEqualTo(second.orderId());
		assertThat(refundPersistence.findAll()).hasSize(2);
		assertThat(orderPersistence.getOrderStatusById(first.orderId())).isEqualTo(OrderStatus.CANCELED);
		assertThat(orderPersistence.getOrderStatusById(second.orderId())).isEqualTo(OrderStatus.CANCELED);
	}

	@DisplayName("남의 주문 번호로는 취소할 수 없고 환불도 생기지 않는다")
	@Test
	void cancel_whenOrderBelongsToAnotherMember_rejects() {
		Fixture fixture = paidOrder();
		Member other = memberPersistence.save(member("other"));

		assertThatThrownBy(() ->
			cancelOrderUseCase.cancel(other.getId(), fixture.orderId(), "cancel-key-3", List.of()))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));

		assertThat(refundPersistence.findAll()).isEmpty();
		then(paymentGatewayPort).should(never()).refund(any(), any(), any());
	}

	@DisplayName("남의 결제 전 주문 번호로는 취소할 수 없고 그 주문이 있는지도, 취소 선점 자원도 드러나지 않는다")
	@Test
	void cancel_whenInitOrderBelongsToAnotherMember_rejectsWithoutLeakingReservation() {
		Member owner = memberPersistence.save(member("init-owner"));
		Product product = productPersistence.save(product());
		Order order = Order.create(owner.getId());
		order.addOrderItem(product.getId(), 1, UNIT_PRICE);
		Order saved = orderPersistence.saveAndFlush(order);
		stockPersistence.save(Stock.create(product.getId(), 0));
		Member stranger = memberPersistence.save(member("init-stranger"));
		String idempotencyKey = "cancel-key-init-stranger";

		assertThatThrownBy(() ->
			cancelOrderUseCase.cancel(stranger.getId(), saved.getId(), idempotencyKey, List.of()))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));

		assertThat(refundPersistence.findAll()).isEmpty();
		assertThat(orderPersistence.getOrderStatusById(saved.getId())).isEqualTo(OrderStatus.INIT);
		// 실패한 요청도 끝에서 선점을 풀어, 요청 전후로 같은 키를 다시 선점할 수 있다.
		boolean reservedAgain = orderIdempotencyStore.reserveCancel(saved.getId(), idempotencyKey, Duration.ofSeconds(60));
		assertThat(reservedAgain).isTrue();
		orderIdempotencyStore.clearCancel(saved.getId(), idempotencyKey);
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
			cancelOrderUseCase.cancel(member.getId(), savedOrder.getId(), "cancel-key-4", List.of()))
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
			fixture.memberId(), fixture.orderId(), "cancel-key-5", List.of());

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
			fixture.memberId(), fixture.orderId(), "cancel-key-6", List.of());

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
			RefundReason.ORDER_NOT_PAYABLE.name(), List.of());

		Payment payment = paymentPersistence.findById(fixture.paymentId()).orElseThrow();
		Refund systemRefund = Refund.open(payment.getId(), "RF-system-" + uniqueSuffix,
			RefundRequester.SYSTEM, RefundReason.ORDER_NOT_PAYABLE.name(), 1, RefundReason.ORDER_NOT_PAYABLE);
		refundPersistence.save(systemRefund);

		assertThat(refundPersistence.findAll()).hasSize(2);
	}

	@DisplayName("결제를 시작하지 않은 결제 전 주문의 취소가 거부되고 주문 상태·재고가 그대로 남는다")
	@Test
	void cancel_whenOrderNotPaidAndNoPaymentAttached_rejectsWithoutChangingAnything() {
		Member member = memberPersistence.save(member("init"));
		Product product = productPersistence.save(product());
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 1, UNIT_PRICE);
		Order saved = orderPersistence.saveAndFlush(order);
		stockPersistence.save(Stock.create(product.getId(), 0));

		assertThatThrownBy(() ->
			cancelOrderUseCase.cancel(member.getId(), saved.getId(), "cancel-key-7", List.of()))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED));

		assertThat(orderPersistence.getOrderStatusById(saved.getId())).isEqualTo(OrderStatus.INIT);
		assertThat(stockPersistence.findByProductId(product.getId()).orElseThrow().getQuantity()).isZero();
		assertThat(refundPersistence.findAll()).isEmpty();
	}

	@DisplayName("결제창이 열려 활성 슬롯을 쥔 결제가 걸린 결제 전 주문의 취소도 거부되고 그 결제 상태도 그대로다")
	@Test
	void cancel_whenOrderNotPaidAndPaymentInProgress_rejectsWithoutChangingPayment() {
		int suffix = ++uniqueSuffix;
		Member member = memberPersistence.save(member("inprogress" + suffix));
		Product product = productPersistence.save(product());
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 1, UNIT_PRICE);
		Order saved = orderPersistence.saveAndFlush(order);
		stockPersistence.save(Stock.create(product.getId(), 0));

		Payment inProgress = Payment.start(saved.getId(), member.getId(), PaymentPg.NAVERPAY,
			"PK-inprogress-" + suffix, "idem-inprogress-" + suffix, UNIT_PRICE);
		inProgress.markInProgress("pg-inprogress-" + suffix, LocalDateTime.now());
		Payment savedPayment = paymentPersistence.save(inProgress);

		assertThatThrownBy(() ->
			cancelOrderUseCase.cancel(member.getId(), saved.getId(), "cancel-key-inprogress", List.of()))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED));

		assertThat(orderPersistence.getOrderStatusById(saved.getId())).isEqualTo(OrderStatus.INIT);
		assertThat(paymentPersistence.findById(savedPayment.getId()).orElseThrow().getStatus())
			.isEqualTo(PaymentStatus.IN_PROGRESS);
		assertThat(refundPersistence.findAll()).isEmpty();
	}

	@DisplayName("결제가 실패·거절로 종결되어 걸린 결제가 없는 결제 전 주문의 취소도 같은 오류로 거부된다")
	@Test
	void cancel_whenOrderNotPaidAndPaymentClosed_rejectsWithSameError() {
		int suffix = ++uniqueSuffix;
		Member member = memberPersistence.save(member("closed" + suffix));
		Product product = productPersistence.save(product());
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 1, UNIT_PRICE);
		Order saved = orderPersistence.saveAndFlush(order);
		stockPersistence.save(Stock.create(product.getId(), 0));

		Payment closed = Payment.start(saved.getId(), member.getId(), PaymentPg.NAVERPAY,
			"PK-closed-" + suffix, "idem-closed-" + suffix, UNIT_PRICE);
		closed.markInProgress("pg-closed-" + suffix, LocalDateTime.now());
		closed.fail(PaymentCloseCode.PG_DECLINED, "거절");
		paymentPersistence.save(closed);

		assertThatThrownBy(() ->
			cancelOrderUseCase.cancel(member.getId(), saved.getId(), "cancel-key-closed", List.of()))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED));

		assertThat(orderPersistence.getOrderStatusById(saved.getId())).isEqualTo(OrderStatus.INIT);
		assertThat(refundPersistence.findAll()).isEmpty();
	}

	@DisplayName("승인 결과를 모르는 결제가 걸린 결제 전 주문의 취소도 같은 취소 불가 오류로 거부된다")
	@Test
	void cancel_whenOrderNotPaidAndUnknownPaymentExists_rejectsWithSameError() {
		int suffix = ++uniqueSuffix;
		Member member = memberPersistence.save(member("unknowninit" + suffix));
		Product product = productPersistence.save(product());
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 1, UNIT_PRICE);
		Order saved = orderPersistence.saveAndFlush(order);
		stockPersistence.save(Stock.create(product.getId(), 0));

		Payment unknown = Payment.start(saved.getId(), member.getId(), PaymentPg.NAVERPAY,
			"PK-unknown-init-" + suffix, "idem-unknown-init-" + suffix, UNIT_PRICE);
		unknown.markInProgress("pg-unknown-init-" + suffix, LocalDateTime.now());
		unknown.markUnknown();
		paymentPersistence.save(unknown);

		// 승인 결과를 모르는 결제를 보는 검사보다 취소 가능 판정이 앞이라, "결제 확인 중"이 아니라 다른
		// 결제 전 주문과 같은 취소 불가 오류가 나간다.
		assertThatThrownBy(() ->
			cancelOrderUseCase.cancel(member.getId(), saved.getId(), "cancel-key-unknown-init", List.of()))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED));

		assertThat(orderPersistence.getOrderStatusById(saved.getId())).isEqualTo(OrderStatus.INIT);
		assertThat(refundPersistence.findAll()).isEmpty();
	}

	@DisplayName("결제 전 주문에 같은 취소 요청을 두 번 보내도 두 번 다 거부되고 아무것도 바뀌지 않는다")
	@Test
	void cancel_whenSameKeySentTwiceToInitOrder_rejectsBothTimes() {
		Member member = memberPersistence.save(member("twice"));
		Product product = productPersistence.save(product());
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 1, UNIT_PRICE);
		Order saved = orderPersistence.saveAndFlush(order);
		stockPersistence.save(Stock.create(product.getId(), 0));
		String idempotencyKey = "cancel-key-twice";

		assertThatThrownBy(() ->
			cancelOrderUseCase.cancel(member.getId(), saved.getId(), idempotencyKey, List.of()))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED));
		// 첫 요청이 끝에서 선점을 풀어 두므로 두 번째 요청도 "처리 중"이 아니라 같은 거부를 받는다.
		assertThatThrownBy(() ->
			cancelOrderUseCase.cancel(member.getId(), saved.getId(), idempotencyKey, List.of()))
			.isInstanceOf(OrderException.class)
			.satisfies(ex -> assertThat(((OrderException) ex).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED));

		assertThat(orderPersistence.getOrderStatusById(saved.getId())).isEqualTo(OrderStatus.INIT);
		assertThat(stockPersistence.findByProductId(product.getId()).orElseThrow().getQuantity()).isZero();
		assertThat(refundPersistence.findAll()).isEmpty();
	}

	// ── 부분취소 ─────────────────────────────────────────────

	@DisplayName("품목과 수량을 지정해 일부만 취소하면 그 값어치만 환불되고 주문은 결제완료로 남는다")
	@Test
	void cancel_whenPartialQuantityRequested_refundsThatValueAndKeepsOrderPaid() {
		BaseOrder base = baseOrder(1);
		givenGatewaySucceeds();

		OrderCancelResult result = cancel(base, "partial-key-1", List.of(new OrderCancelLine(base.appleItemId(), 1)));

		assertThat(result.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(result.getRefundedAmount()).isEqualTo(APPLE_UNIT_PRICE);
		assertThat(result.getRemainingAmount()).isEqualTo(40_000);
		assertThat(orderPersistence.getCancelledQuantity(base.appleItemId())).isEqualTo(1);
		assertThat(orderPersistence.getCancelledQuantity(base.pearItemId())).isZero();
		assertThat(stockPersistence.findByProductId(base.appleProductId()).orElseThrow().getQuantity()).isEqualTo(1);
		assertThat(stockPersistence.findByProductId(base.pearProductId()).orElseThrow().getQuantity()).isZero();
		assertThat(refundPersistence.findAll()).hasSize(1);
	}

	@DisplayName("이어서 다른 품목을 새 키로 취소하면 그 결제에 환불이 둘이 된다")
	@Test
	void cancel_whenAnotherItemCancelledNext_opensSecondRefund() {
		BaseOrder base = baseOrder(1);
		givenGatewaySucceeds();
		cancel(base, "partial-key-2a", List.of(new OrderCancelLine(base.appleItemId(), 1)));

		OrderCancelResult result = cancel(base, "partial-key-2b", List.of(new OrderCancelLine(base.pearItemId(), 1)));

		assertThat(result.getRefundedAmount()).isEqualTo(PEAR_UNIT_PRICE);
		assertThat(refundPersistence.findAll()).hasSize(2);
		assertThat(refundPersistence.findAll()).extracting(Refund::getAmount)
			.containsExactlyInAnyOrder(APPLE_UNIT_PRICE, PEAR_UNIT_PRICE);
	}

	@DisplayName("품목 목록을 싣지 않으면 남은 수량 전부가 취소되고 주문이 취소로 전이한다")
	@Test
	void cancel_whenItemsOmitted_cancelsEveryRemainingQuantity() {
		BaseOrder base = baseOrder(1);
		givenGatewaySucceeds();
		cancel(base, "partial-key-3a", List.of(new OrderCancelLine(base.appleItemId(), 1)));

		OrderCancelResult result = cancel(base, "partial-key-3b", List.of());

		assertThat(result.getRefundedAmount()).isEqualTo(40_000);
		assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELED);
		assertThat(stockPersistence.findByProductId(base.appleProductId()).orElseThrow().getQuantity())
			.isEqualTo(APPLE_QUANTITY);
		assertThat(stockPersistence.findByProductId(base.pearProductId()).orElseThrow().getQuantity()).isEqualTo(1);
	}

	@DisplayName("잔여수량을 그대로 지정해도 품목 목록을 생략했을 때와 같은 결과가 된다")
	@Test
	void cancel_whenRemainingQuantitySpecified_matchesOmittingItems() {
		BaseOrder base = baseOrder(1);
		givenGatewaySucceeds();
		cancel(base, "partial-key-4a", List.of(new OrderCancelLine(base.appleItemId(), 1)));

		OrderCancelResult result = cancel(base, "partial-key-4b", List.of(
			new OrderCancelLine(base.appleItemId(), 2),
			new OrderCancelLine(base.pearItemId(), 1)));

		assertThat(result.getRefundedAmount()).isEqualTo(40_000);
		assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELED);
	}

	@DisplayName("취소에 어느 품목을 몇 개 취소했는지가 남고 그 값어치가 환불 금액과 맞는다")
	@Test
	void cancel_whenApplied_recordsCancelledItemsWorthTheRefundAmount() {
		BaseOrder base = baseOrder(1);
		givenGatewaySucceeds();

		cancel(base, "partial-key-5", List.of(
			new OrderCancelLine(base.appleItemId(), 2),
			new OrderCancelLine(base.pearItemId(), 1)));

		Refund refund = refundPersistence.findAll().get(0);
		Map<Long, Integer> cancelled = orderPersistence.findCancelledQuantitiesByRefundId(refund.getId());
		assertThat(cancelled).containsOnly(
			entry(base.appleItemId(), 2),
			entry(base.pearItemId(), 1));
		int worth = cancelled.get(base.appleItemId()) * APPLE_UNIT_PRICE
			+ cancelled.get(base.pearItemId()) * PEAR_UNIT_PRICE;
		assertThat(worth).isEqualTo(refund.getAmount());
	}

	@DisplayName("주문이 결제완료로 남아 있어도 같은 키 재요청은 앞 결과를 그대로 돌려준다")
	@Test
	void cancel_whenSameKeyRetriedWhileOrderStillPaid_replaysPreviousResult() {
		BaseOrder base = baseOrder(1);
		givenGatewaySucceeds();
		List<OrderCancelLine> lines = List.of(new OrderCancelLine(base.appleItemId(), 1));

		OrderCancelResult first = cancel(base, "partial-key-6", lines);
		OrderCancelResult second = cancel(base, "partial-key-6", lines);

		assertThat(second.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(second.getRefundedAmount()).isEqualTo(first.getRefundedAmount());
		assertThat(second.getRemainingAmount()).isEqualTo(first.getRemainingAmount());
		assertThat(refundPersistence.findAll()).hasSize(1);
		assertThat(orderPersistence.getCancelledQuantity(base.appleItemId())).isEqualTo(1);
		assertThat(stockPersistence.findByProductId(base.appleProductId()).orElseThrow().getQuantity()).isEqualTo(1);
		then(paymentGatewayPort).should().refund(any(), any(), any());
	}

	@DisplayName("품목 순서만 다른 재시도는 같은 요청으로 보아 앞 결과를 돌려준다")
	@Test
	void cancel_whenSameKeyRetriedWithItemsInAnotherOrder_replaysPreviousResult() {
		BaseOrder base = baseOrder(1);
		givenGatewaySucceeds();
		cancel(base, "partial-key-7", List.of(
			new OrderCancelLine(base.appleItemId(), 2),
			new OrderCancelLine(base.pearItemId(), 1)));

		OrderCancelResult replayed = cancel(base, "partial-key-7", List.of(
			new OrderCancelLine(base.pearItemId(), 1),
			new OrderCancelLine(base.appleItemId(), 2)));

		assertThat(replayed.getRefundedAmount()).isEqualTo(40_000);
		assertThat(refundPersistence.findAll()).hasSize(1);
		assertThat(orderPersistence.getCancelledQuantity(base.appleItemId())).isEqualTo(2);
		assertThat(stockPersistence.findByProductId(base.appleProductId()).orElseThrow().getQuantity()).isEqualTo(2);
	}

	@DisplayName("같은 키로 다른 품목을 보내면 거절되고 아무 상태도 바뀌지 않는다")
	@Test
	void cancel_whenSameKeyCarriesDifferentItems_rejects() {
		BaseOrder base = baseOrder(1);
		givenGatewaySucceeds();
		cancel(base, "partial-key-8", List.of(new OrderCancelLine(base.appleItemId(), 1)));

		assertThatThrownBy(() ->
			cancel(base, "partial-key-8", List.of(new OrderCancelLine(base.pearItemId(), 1))))
			.isInstanceOf(PaymentException.class)
			.satisfies(ex -> assertThat(((PaymentException) ex).getErrorCode())
				.isEqualTo(PaymentErrorCode.REFUND_IDEMPOTENCY_KEY_CONFLICT));

		assertThat(refundPersistence.findAll()).hasSize(1);
		assertThat(orderPersistence.getCancelledQuantity(base.pearItemId())).isZero();
		assertThat(stockPersistence.findByProductId(base.pearProductId()).orElseThrow().getQuantity()).isZero();
	}

	@DisplayName("금액이 같아도 품목 조합이 다르면 같은 키로 온 요청이 거절된다")
	@Test
	void cancel_whenSameKeyCarriesAnotherCombinationOfSameValue_rejects() {
		BaseOrder base = baseOrder(1);
		givenGatewaySucceeds();
		// 1만원 사과 2개와 2만원 배 1개는 값어치가 같다.
		cancel(base, "partial-key-9", List.of(new OrderCancelLine(base.appleItemId(), 2)));

		assertThatThrownBy(() ->
			cancel(base, "partial-key-9", List.of(new OrderCancelLine(base.pearItemId(), 1))))
			.isInstanceOf(PaymentException.class)
			.satisfies(ex -> assertThat(((PaymentException) ex).getErrorCode())
				.isEqualTo(PaymentErrorCode.REFUND_IDEMPOTENCY_KEY_CONFLICT));

		assertThat(refundPersistence.findAll()).hasSize(1);
		assertThat(orderPersistence.getCancelledQuantity(base.pearItemId())).isZero();
	}

	@DisplayName("취소 품목 내역이 없는 옛 환불의 키에 품목 목록을 실으면 거절되고, 목록 없는 재시도는 재생된다")
	@Test
	void cancel_whenPreviousRefundHasNoItemRecord_rejectsRequestCarryingItems() {
		BaseOrder base = baseOrder(1);
		// 취소 품목 내역을 남기기 전에 열린 환불이라 무엇을 취소했는지 알 수 없다.
		Payment payment = paymentPersistence.findById(base.paymentId()).orElseThrow();
		refundPersistence.save(Refund.open(payment.getId(), "RF-legacy-" + (++uniqueSuffix),
			RefundRequester.MEMBER, "legacy-key", APPLE_UNIT_PRICE, RefundReason.ORDER_CANCELED));

		assertThatThrownBy(() ->
			cancel(base, "legacy-key", List.of(new OrderCancelLine(base.appleItemId(), 1))))
			.isInstanceOf(PaymentException.class)
			.satisfies(ex -> assertThat(((PaymentException) ex).getErrorCode())
				.isEqualTo(PaymentErrorCode.REFUND_IDEMPOTENCY_KEY_CONFLICT));

		OrderCancelResult replayed = cancel(base, "legacy-key", List.of());

		assertThat(replayed.getRefundedAmount()).isEqualTo(APPLE_UNIT_PRICE);
		assertThat(orderPersistence.getCancelledQuantity(base.appleItemId())).isZero();
		assertThat(orderPersistence.countCancellations()).isZero();
		then(paymentGatewayPort).should(never()).refund(any(), any(), any());
	}

	@DisplayName("잔여 전부를 취소하면 그 값어치가 결제의 남은 한도와 같아 한도에 걸리지 않는다")
	@Test
	void cancel_whenRemainingCancelledAfterPartial_matchesRefundLimit() {
		// 1만원 사과 3개와 2만원 배 2개로 7만원을 결제한 주문에서 배 1개가 먼저 취소된다.
		BaseOrder base = baseOrder(2);
		givenGatewaySucceeds();
		cancel(base, "partial-key-10a", List.of(new OrderCancelLine(base.pearItemId(), 1)));

		OrderCancelResult result = cancel(base, "partial-key-10b", List.of());

		assertThat(result.getRefundedAmount()).isEqualTo(50_000);
		assertThat(result.getRemainingAmount()).isZero();
		assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELED);
	}

	@DisplayName("환불이 사람 손으로 넘어가도 취소수량과 늘어난 재고는 되돌아가지 않는다")
	@Test
	void cancel_whenRefundEndsInManualReview_keepsCancelledQuantityAndStock() {
		BaseOrder base = baseOrder(1);
		given(paymentGatewayPort.refund(any(), any(), any()))
			.willReturn(PgRefundResult.terminalFailure(RefundReviewCode.CANCEL_DEADLINE_EXPIRED,
				"취소 기한 만료", new PgCallRecord(PgErrorType.NONE, "CancelDeadlineExpired", 200, "{}")));

		OrderCancelResult result = cancel(base, "partial-key-11", List.of(new OrderCancelLine(base.appleItemId(), 1)));

		assertThat(result.getRefundStatus()).isEqualTo(OrderCancelRefundStatus.IN_PROGRESS);
		assertThat(refundPersistence.findAll().get(0).getStatus()).isEqualTo(RefundStatus.MANUAL_REVIEW);
		assertThat(orderPersistence.getCancelledQuantity(base.appleItemId())).isEqualTo(1);
		assertThat(stockPersistence.findByProductId(base.appleProductId()).orElseThrow().getQuantity()).isEqualTo(1);
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
		return paidOrderFor(member.getId());
	}

	private Fixture paidOrderFor(Long memberId) {
		int suffix = ++uniqueSuffix;
		Product product = productPersistence.save(product());
		Order order = Order.create(memberId);
		order.addOrderItem(product.getId(), 1, UNIT_PRICE);
		order.completePayment();
		Order savedOrder = orderPersistence.saveAndFlush(order);
		stockPersistence.save(Stock.create(product.getId(), 0));

		Payment payment = Payment.start(savedOrder.getId(), memberId, PaymentPg.NAVERPAY,
			"PK-" + suffix, "idem-" + suffix, UNIT_PRICE);
		payment.markInProgress("pg-payment-" + suffix, LocalDateTime.now());
		payment.succeed(UNIT_PRICE, "pg-tx-" + suffix);
		Payment savedPayment = paymentPersistence.save(payment);

		return new Fixture(memberId, savedOrder.getId(), savedPayment.getId(), product.getId());
	}

	private Member member(String tag) {
		return Member.createUser(
			"cancel-uc-" + tag + "-" + UUID.randomUUID().toString().substring(0, 6) + "@example.com",
			"password123", "u-" + UUID.randomUUID().toString().substring(0, 5));
	}

	private Product product() {
		return product(UNIT_PRICE);
	}

	private Product product(int price) {
		return Product.create("상품-" + UUID.randomUUID().toString().substring(0, 6),
			price, null, null, ProductStatus.ON_SALE);
	}

	private record Fixture(Long memberId, Long orderId, Long paymentId, Long productId) {
	}

	private OrderCancelResult cancel(BaseOrder base, String idempotencyKey, List<OrderCancelLine> lines) {
		return cancelOrderUseCase.cancel(base.memberId(), base.orderId(), idempotencyKey, lines);
	}

	/** 1만원 사과 3개와 2만원 배로 이루어진 기준 주문. 전액 승인이 끝났고 두 상품의 재고는 0이다 */
	private BaseOrder baseOrder(int pearQuantity) {
		int suffix = ++uniqueSuffix;
		Member member = memberPersistence.save(member("base" + suffix));
		Product apple = productPersistence.save(product(APPLE_UNIT_PRICE));
		Product pear = productPersistence.save(product(PEAR_UNIT_PRICE));

		Order order = Order.create(member.getId());
		order.addOrderItem(apple.getId(), APPLE_QUANTITY, APPLE_UNIT_PRICE);
		order.addOrderItem(pear.getId(), pearQuantity, PEAR_UNIT_PRICE);
		order.completePayment();
		Order savedOrder = orderPersistence.saveAndFlush(order);
		stockPersistence.save(Stock.create(apple.getId(), 0));
		stockPersistence.save(Stock.create(pear.getId(), 0));

		int totalPrice = savedOrder.getTotalPrice();
		Payment payment = Payment.start(savedOrder.getId(), member.getId(), PaymentPg.NAVERPAY,
			"PK-base-" + suffix, "idem-base-" + suffix, totalPrice);
		payment.markInProgress("pg-payment-base-" + suffix, LocalDateTime.now());
		payment.succeed(totalPrice, "pg-tx-base-" + suffix);
		Payment savedPayment = paymentPersistence.save(payment);

		return new BaseOrder(member.getId(), savedOrder.getId(), savedPayment.getId(),
			apple.getId(), pear.getId(),
			savedOrder.getOrderItems().get(0).getId(), savedOrder.getOrderItems().get(1).getId());
	}

	private record BaseOrder(
		Long memberId, Long orderId, Long paymentId,
		Long appleProductId, Long pearProductId, Long appleItemId, Long pearItemId) {
	}
}
