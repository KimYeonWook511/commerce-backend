package com.commerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

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
		assertThat(response.getReturnUrl()).startsWith("https://return-url");
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

		PaymentReadyServiceRequest request = PaymentReadyServiceRequest.builder()
			.memberId(1L)
			.orderId(1L)
			.provider(PaymentProvider.NAVERPAY)
			.build();

		// when
		PaymentReadyResponse response = paymentService.readyPayment(request);

		// then
		assertThat(response.getMerchantPayKey()).isEqualTo("PAY-EXIST");
		then(paymentRepository).should(never()).save(any(Payment.class));
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

	@DisplayName("결제를 실패 처리하면 상태가 FAILED로 바뀐다")
	@Test
	void failPayment_whenPaymentExists_changeStatus() {
		// given
		Payment payment = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		ReflectionTestUtils.setField(payment, "status", PaymentStatus.PROCESSING);
		given(paymentRepository.findByOrderId(1L)).willReturn(Optional.of(payment));

		// when
		Payment result = paymentService.failPayment(1L, "fail");

		// then
		assertThat(result.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(result.getFailureReason()).isEqualTo("fail");
		assertThat(result.getPgPaymentId()).isNull();
	}

	@DisplayName("결제가 없으면 실패 처리에 실패한다")
	@Test
	void failPayment_whenPaymentNotFound_throwException() {
		// given
		given(paymentRepository.findByOrderId(1L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> paymentService.failPayment(1L, "fail"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
			});
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

	@DisplayName("결제가 없으면 취소 처리에 실패한다")
	@Test
	void cancelPayment_whenPaymentNotFound_throwException() {
		// given
		given(paymentRepository.findByOrderId(1L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> paymentService.cancelPayment(1L, "cancel"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
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
		given(paymentRepository.findByMerchantPayKey("PAY-1")).willReturn(Optional.of(payment));

		// when
		Payment result = paymentService.completePayment("PAY-1", "pg-payment-id", java.time.LocalDateTime.now());

		// then
		assertThat(result.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
		assertThat(result.getPgPaymentId()).isEqualTo("pg-payment-id");
	}

	@DisplayName("결제가 처리 중이면 실패 처리에 성공한다")
	@Test
	void failPaymentByMerchantPayKey_whenProcessing_changeStatus() {
		// given
		Payment payment = Payment.create(createOrder(1000), 1000, PaymentProvider.NAVERPAY);
		ReflectionTestUtils.setField(payment, "status", PaymentStatus.PROCESSING);
		given(paymentRepository.findByMerchantPayKey("PAY-1")).willReturn(Optional.of(payment));

		// when
		Payment result = paymentService.failPayment("PAY-1", null, "fail");

		// then
		assertThat(result.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(result.getFailureReason()).isEqualTo("fail");
		assertThat(result.getPgPaymentId()).isNull();
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
