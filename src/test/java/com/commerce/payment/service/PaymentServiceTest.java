package com.commerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.member.domain.Member;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.order.repository.OrderRepository;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReasonCode;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.provider.PaymentProviderProperties;
import com.commerce.payment.provider.PaymentProviderPropertiesResolver;
import com.commerce.payment.repository.PaymentRepository;
import com.commerce.payment.service.command.PaymentReadyCommand;
import com.commerce.payment.service.result.PaymentReadyResult;
import com.commerce.product.domain.Product;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private PaymentProviderPropertiesResolver propertiesResolver;

	@Mock
	private PaymentProviderProperties providerProperties;

	@InjectMocks
	private PaymentService paymentService;

	@DisplayName("결제 준비 요청을 하면 결제 준비 응답을 반환한다")
	@Test
	void readyPayment_whenOrderExists_returnReadyResponse() {
		// given
		Order order = createOrder(1500);
		setOrderId(order, 1L);
		given(orderRepository.findByIdAndMemberIdWithItems(1L, 1L)).willReturn(Optional.of(order));
		given(paymentRepository.findByOrderId(1L)).willReturn(Optional.empty());
		given(paymentRepository.save(any(Payment.class)))
			.willAnswer(invocation -> {
				Payment saved = invocation.getArgument(0);
				ReflectionTestUtils.setField(saved, "id", 10L);
				return saved;
			});
		stubPaymentProperties();

		PaymentReadyCommand command = PaymentReadyCommand.builder()
			.memberId(1L)
			.orderId(1L)
			.provider(PaymentProvider.NAVERPAY)
			.build();

		// when
		PaymentReadyResult result = paymentService.readyPayment(command);

		// then
		assertThat(result.getClientId()).isEqualTo("client-id");
		assertThat(result.getChainId()).isEqualTo("chain-id");
		assertThat(result.getMerchantPayKey()).startsWith("PAY-");
		assertThat(result.getMerchantPayKey()).hasSize(30);
		assertThat(result.getProductName()).isEqualTo("product");
		assertThat(result.getProductCount()).isEqualTo(1);
		assertThat(result.getTotalPayAmount()).isEqualTo(1500);
		assertThat(result.getTaxScopeAmount()).isEqualTo(1500);
		assertThat(result.getTaxExScopeAmount()).isZero();
		assertThat(result.getReturnUrl()).startsWith("https://return-url");
	}

	@DisplayName("이미 결제가 있으면 기존 결제 정보를 사용한다")
	@Test
	void readyPayment_whenPaymentExists_useExistingPayment() {
		// given
		Order order = createOrder(1500);
		setOrderId(order, 1L);
		Payment existing = Payment.create(order, 1500, PaymentProvider.NAVERPAY);
		ReflectionTestUtils.setField(existing, "merchantPayKey", "PAY-EXIST");

		given(orderRepository.findByIdAndMemberIdWithItems(1L, 1L)).willReturn(Optional.of(order));
		given(paymentRepository.findByOrderId(1L)).willReturn(Optional.of(existing));
		stubPaymentProperties();

		PaymentReadyCommand command = PaymentReadyCommand.builder()
			.memberId(1L)
			.orderId(1L)
			.provider(PaymentProvider.NAVERPAY)
			.build();

		// when
		PaymentReadyResult result = paymentService.readyPayment(command);

		// then
		assertThat(result.getMerchantPayKey()).isEqualTo("PAY-EXIST");
		then(paymentRepository).should(never()).save(any(Payment.class));
	}

	@DisplayName("주문이 없으면 결제 준비에 실패한다")
	@Test
	void readyPayment_whenOrderNotFound_throwException() {
		// given
		given(orderRepository.findByIdAndMemberIdWithItems(1L, 1L)).willReturn(Optional.empty());

		PaymentReadyCommand command = PaymentReadyCommand.builder()
			.memberId(1L)
			.orderId(1L)
			.provider(PaymentProvider.NAVERPAY)
			.build();

		// when & then
		assertThatThrownBy(() -> paymentService.readyPayment(command))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException)exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);
			});
	}

	@DisplayName("주문 상품이 없으면 결제 준비에 실패한다")
	@Test
	void readyPayment_whenOrderItemsEmpty_throwException() {
		// given
		Order order = Order.create(createMember());
		setOrderId(order, 1L);
		given(orderRepository.findByIdAndMemberIdWithItems(1L, 1L)).willReturn(Optional.of(order));
		given(paymentRepository.findByOrderId(1L)).willReturn(Optional.empty());
		given(paymentRepository.save(any(Payment.class)))
			.willAnswer(invocation -> {
				Payment saved = invocation.getArgument(0);
				ReflectionTestUtils.setField(saved, "id", 10L);
				return saved;
			});
		stubPaymentResolver();

		PaymentReadyCommand command = PaymentReadyCommand.builder()
			.memberId(1L)
			.orderId(1L)
			.provider(PaymentProvider.NAVERPAY)
			.build();

		// when & then
		assertThatThrownBy(() -> paymentService.readyPayment(command))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException) exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_ITEMS_EMPTY);
			});
	}

	@DisplayName("결제를 처리 중으로 마킹하면 업데이트 수를 반환한다")
	@Test
	void markProcessing_whenCalled_returnUpdatedCount() {
		// given
		given(paymentRepository.updateStatusIfMatches(
			"PAY-1", PaymentStatus.PENDING, PaymentStatus.PROCESSING)).willReturn(1);

		// when
		int updated = paymentService.markProcessing("PAY-1");

		// then
		assertThat(updated).isEqualTo(1);
	}

	@DisplayName("결제가 처리 중이면 완료 처리에 성공한다")
	@Test
	void completePayment_whenProcessing_changeStatus() {
		// given
		Payment payment = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		ReflectionTestUtils.setField(payment, "status", PaymentStatus.PROCESSING);
		given(paymentRepository.findByMerchantPayKeyWithOrder("PAY-1")).willReturn(Optional.of(payment));

		// when
		Payment result = paymentService.completePayment("PAY-1", "pg-payment-id", java.time.LocalDateTime.now());

		// then
		assertThat(result.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
		assertThat(result.getPgPaymentId()).isEqualTo("pg-payment-id");
		assertThat(result.getOrder().getStatus()).isEqualTo(OrderStatus.PAID);
	}

	@DisplayName("주문이 초기 상태가 아니면 결제 완료 처리에 실패한다")
	@Test
	void completePayment_whenOrderNotInit_throwException() {
		// given
		Order order = createOrder(1000);
		ReflectionTestUtils.setField(order, "status", OrderStatus.RECEIVED);
		Payment payment = Payment.create(order, 1000, PaymentProvider.NAVERPAY);
		ReflectionTestUtils.setField(payment, "status", PaymentStatus.PROCESSING);
		given(paymentRepository.findByMerchantPayKeyWithOrder("PAY-1")).willReturn(Optional.of(payment));

		// when & then
		assertThatThrownBy(() -> paymentService.completePayment("PAY-1", "pg-payment-id", LocalDateTime.now()))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException) exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_PAID_NOT_ALLOWED);
			});
	}

	@DisplayName("결제가 없으면 완료 처리에 실패한다")
	@Test
	void completePayment_whenPaymentNotFound_throwException() {
		// given
		given(paymentRepository.findByMerchantPayKeyWithOrder("PAY-1")).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> paymentService.completePayment("PAY-1", "pg-payment-id", LocalDateTime.now()))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException) exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
			});
	}

	@DisplayName("결제가 처리 중이면 실패 처리에 성공한다")
	@Test
	void failPaymentByMerchantPayKey_whenProcessing_changeStatus() {
		// given
		Payment payment = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		given(paymentRepository.findByMerchantPayKey("PAY-1")).willReturn(Optional.of(payment));

		// when
		Payment result = paymentService.failPayment("PAY-1", PaymentReasonCode.APPROVAL_FAILED, "fail");

		// then
		assertThat(result.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(result.getReasonCode()).isEqualTo(PaymentReasonCode.APPROVAL_FAILED);
		assertThat(result.getReasonDetail()).isEqualTo("fail");
	}

	@DisplayName("결제가 없으면 실패 처리에 실패한다")
	@Test
	void failPaymentByMerchantPayKey_whenPaymentNotFound_throwException() {
		// given
		given(paymentRepository.findByMerchantPayKey("PAY-1")).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> paymentService.failPayment("PAY-1", PaymentReasonCode.APPROVAL_FAILED, "fail"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException) exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
			});
	}

	@DisplayName("결제 취소 대기 상태로 전환하면 업데이트 수를 반환한다")
	@Test
	void markCancelPending_whenCalled_returnUpdatedCount() {
		// given
		given(paymentRepository.updateToCancelPending(
			"PAY-1",
			PaymentStatus.PROCESSING,
			PaymentStatus.CANCEL_PENDING,
			"pg-payment-id",
			PaymentReasonCode.AMOUNT_MISMATCH,
			"mismatch"
		)).willReturn(1);

		// when
		int updated = paymentService.markCancelPending(
			"PAY-1",
			"pg-payment-id",
			PaymentReasonCode.AMOUNT_MISMATCH,
			"mismatch"
		);

		// then
		assertThat(updated).isEqualTo(1);
	}

	@DisplayName("결제 취소 완료 시 주문도 취소 처리된다")
	@Test
	void completeCancelPayment_whenCalled_updateStatus() {
		// given
		Payment payment = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		ReflectionTestUtils.setField(payment, "status", PaymentStatus.CANCEL_PENDING);
		ReflectionTestUtils.setField(payment.getOrder(), "status", OrderStatus.PAID);
		given(paymentRepository.findByPgPaymentIdWithOrder("pg-payment-id")).willReturn(Optional.of(payment));

		// when
		Payment result = paymentService.completeCancelPayment("pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(PaymentStatus.CANCELED);
		assertThat(result.getOrder().getStatus()).isEqualTo(OrderStatus.CANCELED);
	}

	@DisplayName("결제 취소 완료 시 주문이 PAID가 아니면 주문 상태는 그대로 두고 결제만 취소 완료 처리한다")
	@Test
	void completeCancelPayment_whenOrderNotPaid_keepOrderStatus() {
		// given
		Payment payment = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		ReflectionTestUtils.setField(payment, "status", PaymentStatus.CANCEL_PENDING);
		ReflectionTestUtils.setField(payment.getOrder(), "status", OrderStatus.INIT);
		given(paymentRepository.findByPgPaymentIdWithOrder("pg-payment-id")).willReturn(Optional.of(payment));

		// when
		Payment result = paymentService.completeCancelPayment("pg-payment-id");

		// then
		assertThat(result.getStatus()).isEqualTo(PaymentStatus.CANCELED);
		assertThat(result.getOrder().getStatus()).isEqualTo(OrderStatus.INIT);
	}

	@DisplayName("결제 키와 회원 ID가 일치하면 결제를 조회한다")
	@Test
	void getPaymentByMerchantPayKeyAndMemberId_whenExists_returnPayment() {
		// given
		Payment payment = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		given(paymentRepository.findByMerchantPayKeyAndMemberId("PAY-1", 1L))
			.willReturn(Optional.of(payment));

		// when
		Payment result = paymentService.getPaymentByMerchantPayKeyAndMemberId("PAY-1", 1L);

		// then
		assertThat(result).isEqualTo(payment);
	}

	@DisplayName("결제 키와 회원 ID에 해당하는 결제가 없으면 예외가 발생한다")
	@Test
	void getPaymentByMerchantPayKeyAndMemberId_whenNotFound_throwException() {
		// given
		given(paymentRepository.findByMerchantPayKeyAndMemberId("PAY-1", 1L))
			.willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> paymentService.getPaymentByMerchantPayKeyAndMemberId("PAY-1", 1L))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException) exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
			});
	}

	private Order createOrder(int totalPrice) {
		Order order = Order.create(createMember());
		Product product = Product.builder()
			.name("product")
			.price(totalPrice)
			.build();
		order.addOrderItem(product, 1);
		return order;
	}

	private Member createMember() {
		return Member.builder()
			.email("payment@example.com")
			.password("password123")
			.username("payer")
			.build();
	}

	private void setOrderId(Order order, Long orderId) {
		ReflectionTestUtils.setField(order, "id", orderId);
	}

	private void stubPaymentProperties() {
		stubPaymentResolver();
		given(providerProperties.getClientId()).willReturn("client-id");
		given(providerProperties.getChainId()).willReturn("chain-id");
		given(providerProperties.getReturnUrl()).willReturn("https://return-url");
	}

	private void stubPaymentResolver() {
		given(propertiesResolver.resolve(PaymentProvider.NAVERPAY)).willReturn(providerProperties);
	}
}
