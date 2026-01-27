package com.commerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import com.commerce.payment.repository.PaymentRepository;
import com.commerce.product.domain.Product;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private OrderRepository orderRepository;

	@InjectMocks
	private PaymentService paymentService;

	@DisplayName("주문이 존재하면 결제를 생성하고 저장한다")
	@Test
	void createPayment_whenOrderExists_savePayment() {
		// given
		Order order = createOrder(2000);
		given(orderRepository.findById(1L)).willReturn(Optional.of(order));
		given(paymentRepository.save(org.mockito.ArgumentMatchers.any(Payment.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		// when
		Payment payment = paymentService.createPayment(1L, PaymentProvider.NAVERPAY);

		// then
		ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
		then(paymentRepository).should().save(captor.capture());
		Payment saved = captor.getValue();

		assertThat(saved.getOrder()).isEqualTo(order);
		assertThat(saved.getAmount()).isEqualTo(2000);
		assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
	}

	@DisplayName("주문이 없으면 결제 생성에 실패한다")
	@Test
	void createPayment_whenOrderNotFound_throwException() {
		// given
		given(orderRepository.findById(1L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> paymentService.createPayment(1L, PaymentProvider.NAVERPAY))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException) exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);
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
}
