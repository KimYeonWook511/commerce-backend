package com.commerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;

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
import com.commerce.payment.service.request.PaymentReadyServiceRequest;
import com.commerce.payment.service.response.PaymentReadyResponse;
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

		PaymentReadyServiceRequest request = PaymentReadyServiceRequest.builder()
			.memberId(1L)
			.orderId(1L)
			.provider(PaymentProvider.NAVERPAY)
			.build();

		// when
		PaymentReadyResponse response = paymentService.readyPayment(request);

		// then
		assertThat(response.getClientId()).isEqualTo("client-id");
		assertThat(response.getChainId()).isEqualTo("chain-id");
		assertThat(response.getMerchantPayKey()).startsWith("PAY-");
		assertThat(response.getMerchantPayKey()).hasSize(30);
		assertThat(response.getProductName()).isEqualTo("product");
		assertThat(response.getProductCount()).isEqualTo(1);
		assertThat(response.getTotalPayAmount()).isEqualTo(1500);
		assertThat(response.getTaxScopeAmount()).isEqualTo(1500);
		assertThat(response.getTaxExScopeAmount()).isZero();
		assertThat(response.getReturnUrl()).isEqualTo("https://return-url");
	}

	@DisplayName("주문이 없으면 결제 준비에 실패한다")
	@Test
	void readyPayment_whenOrderNotFound_throwException() {
		// given
		given(orderRepository.findByIdAndMemberIdWithItems(1L, 1L)).willReturn(Optional.empty());

		PaymentReadyServiceRequest request = PaymentReadyServiceRequest.builder()
			.memberId(1L)
			.orderId(1L)
			.provider(PaymentProvider.NAVERPAY)
			.build();

		// when & then
		assertThatThrownBy(() -> paymentService.readyPayment(request))
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

		PaymentReadyServiceRequest request = PaymentReadyServiceRequest.builder()
			.memberId(1L)
			.orderId(1L)
			.provider(PaymentProvider.NAVERPAY)
			.build();

		// when & then
		assertThatThrownBy(() -> paymentService.readyPayment(request))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException) exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_ITEMS_EMPTY);
			});
	}

	@DisplayName("결제를 완료하면 상태가 COMPLETED로 바뀐다")
	@Test
	void completePayment_whenPaymentExists_changeStatus() {
		// given
		Payment payment = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		LocalDateTime approvedAt = LocalDateTime.now();
		given(paymentRepository.findByOrderId(1L)).willReturn(Optional.of(payment));

		// when
		Payment result = paymentService.completePayment(1L, approvedAt);

		// then
		assertThat(result.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
		assertThat(result.getApprovedAt()).isEqualTo(approvedAt);
	}

	@DisplayName("결제가 없으면 완료 처리에 실패한다")
	@Test
	void completePayment_whenPaymentNotFound_throwException() {
		// given
		given(paymentRepository.findByOrderId(1L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> paymentService.completePayment(1L, LocalDateTime.now()))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException) exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
			});
	}

	@DisplayName("결제를 실패 처리하면 상태가 FAILED로 바뀐다")
	@Test
	void failPayment_whenPaymentExists_changeStatus() {
		// given
		Payment payment = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		given(paymentRepository.findByOrderId(1L)).willReturn(Optional.of(payment));

		// when
		Payment result = paymentService.failPayment(1L, "fail");

		// then
		assertThat(result.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(result.getFailureReason()).isEqualTo("fail");
	}

	@DisplayName("결제를 취소하면 상태가 CANCELED로 바뀐다")
	@Test
	void cancelPayment_whenPaymentExists_changeStatus() {
		// given
		Payment payment = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		given(paymentRepository.findByOrderId(1L)).willReturn(Optional.of(payment));

		// when
		Payment result = paymentService.cancelPayment(1L, "cancel");

		// then
		assertThat(result.getStatus()).isEqualTo(PaymentStatus.CANCELED);
		assertThat(result.getFailureReason()).isEqualTo("cancel");
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
