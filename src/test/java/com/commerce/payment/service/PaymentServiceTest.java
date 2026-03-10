package com.commerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.member.domain.Member;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.order.repository.OrderRepository;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;
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
	private PaymentAttemptService paymentAttemptService;

	@Mock
	private PaymentProviderPropertiesResolver propertiesResolver;

	@Mock
	private PaymentProviderProperties providerProperties;

	@InjectMocks
	private PaymentService paymentService;

	@DisplayName("결제 준비 요청을 하면 결제 준비 응답을 반환한다")
	@Test
	void readyPayment_whenOrderExists_returnReadyResponse() {
		Order order = createOrder(1500);
		setOrderId(order, 1L);
		given(orderRepository.findByIdAndMemberIdWithItems(1L, 1L)).willReturn(Optional.of(order));
		stubPaymentProperties();

		PaymentReadyCommand command = PaymentReadyCommand.builder()
			.memberId(1L)
			.orderId(1L)
			.provider(PaymentProvider.NAVERPAY)
			.build();

		PaymentReadyResult result = paymentService.readyPayment(command);

		assertThat(result.getClientId()).isEqualTo("client-id");
		assertThat(result.getChainId()).isEqualTo("chain-id");
		assertThat(result.getMerchantPayKey()).startsWith("PAY-");
		assertThat(result.getProductName()).isEqualTo("product");
		assertThat(result.getTotalPayAmount()).isEqualTo(1500);
	}

	@DisplayName("주문이 없으면 결제 준비에 실패한다")
	@Test
	void readyPayment_whenOrderNotFound_throwException() {
		given(orderRepository.findByIdAndMemberIdWithItems(1L, 1L)).willReturn(Optional.empty());

		PaymentReadyCommand command = PaymentReadyCommand.builder()
			.memberId(1L)
			.orderId(1L)
			.provider(PaymentProvider.NAVERPAY)
			.build();

		assertThatThrownBy(() -> paymentService.readyPayment(command))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException)exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);
			});
	}

	@DisplayName("결제를 진행할 수 없는 주문이면 결제 준비에 실패한다")
	@Test
	void readyPayment_whenOrderIsNotPayable_throwException() {
		Order order = createOrder(1500);
		setOrderId(order, 1L);
		ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);
		given(orderRepository.findByIdAndMemberIdWithItems(1L, 1L)).willReturn(Optional.of(order));

		PaymentReadyCommand command = PaymentReadyCommand.builder()
			.memberId(1L)
			.orderId(1L)
			.provider(PaymentProvider.NAVERPAY)
			.build();

		assertThatThrownBy(() -> paymentService.readyPayment(command))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException)exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_PAYMENT_NOT_ALLOWED);
			});
	}

	@DisplayName("결제 완료에 성공하면 payment를 생성한다")
	@Test
	void completeApprove_whenPaymentNotExists_createPayment() {
		Order order = createOrder(1000);
		setOrderId(order, 1L);
		LocalDateTime approvedAt = LocalDateTime.now();
		given(orderRepository.findByMerchantPayKeyForUpdate("PAY-1")).willReturn(Optional.of(order));
		given(paymentRepository.findByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentRepository.saveAndFlush(any(Payment.class))).willAnswer(invocation -> invocation.getArgument(0));

		Payment result = paymentService.completeApprove(
			"PAY-1",
			PaymentProvider.NAVERPAY,
			"pg-payment-id",
			approvedAt
		);

		assertThat(result.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
		assertThat(result.getPgPaymentId()).isEqualTo("pg-payment-id");
		assertThat(result.getMerchantPayKey()).isEqualTo("PAY-1");
		assertThat(result.getOrder().getStatus()).isEqualTo(OrderStatus.PAID);
	}

	@DisplayName("이미 완료된 동일 결제가 있으면 기존 payment를 반환한다")
	@Test
	void completeApprove_whenPaymentExistsAndSameRequest_returnExistingPayment() {
		Order order = createOrder(1000);
		Payment payment = Payment.createCompleted(
			order,
			PaymentProvider.NAVERPAY,
			"PAY-1",
			"pg-payment-id",
			LocalDateTime.now()
		);
		LocalDateTime approvedAt = LocalDateTime.now();

		given(orderRepository.findByMerchantPayKeyForUpdate("PAY-1")).willReturn(Optional.of(order));
		given(paymentRepository.findByMerchantPayKey("PAY-1")).willReturn(Optional.of(payment));

		Payment result = paymentService.completeApprove(
			"PAY-1",
			PaymentProvider.NAVERPAY,
			"pg-payment-id",
			approvedAt
		);

		assertThat(result).isEqualTo(payment);
		then(orderRepository).should().findByMerchantPayKeyForUpdate("PAY-1");
		then(paymentAttemptService).should().succeedApproveAttempt(
			"PAY-1",
			PaymentProvider.NAVERPAY,
			"pg-payment-id",
			approvedAt
		);
		then(paymentRepository).shouldHaveNoMoreInteractions();
	}

	@DisplayName("이미 완료된 결제의 pgPaymentId가 다르면 예외가 발생한다")
	@Test
	void completeApprove_whenExistingPaymentHasDifferentPgPaymentId_throwException() {
		Order order = createOrder(1000);
		Payment payment = Payment.createCompleted(
			order,
			PaymentProvider.NAVERPAY,
			"PAY-1",
			"other-payment-id",
			LocalDateTime.now()
		);

		given(orderRepository.findByMerchantPayKeyForUpdate("PAY-1")).willReturn(Optional.of(order));
		given(paymentRepository.findByMerchantPayKey("PAY-1")).willReturn(Optional.of(payment));

		assertThatThrownBy(() -> paymentService.completeApprove(
			"PAY-1",
			PaymentProvider.NAVERPAY,
			"pg-payment-id",
			LocalDateTime.now()
		))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_DUPLICATE);
			});
	}

	@DisplayName("결제 저장 중 유니크 충돌이 발생하면 중복 결제 예외를 던진다")
	@Test
	void completeApprove_whenDuplicateOnSave_throwDuplicateException() {
		// given
		Order order = createOrder(1000);
		setOrderId(order, 1L);
		given(orderRepository.findByMerchantPayKeyForUpdate("PAY-1")).willReturn(Optional.of(order));
		given(paymentRepository.findByMerchantPayKey("PAY-1")).willReturn(Optional.empty());
		given(paymentRepository.saveAndFlush(any(Payment.class)))
			.willThrow(new DataIntegrityViolationException("duplicate key"));

		// when & then
		assertThatThrownBy(() -> paymentService.completeApprove(
			"PAY-1",
			PaymentProvider.NAVERPAY,
			"pg-payment-id",
			LocalDateTime.now()
		))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_DUPLICATE);
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
