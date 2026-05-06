package com.commerce.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

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
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.payment.application.command.PaymentReadyCommand;
import com.commerce.payment.application.result.PaymentReadyResult;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.provider.PaymentProviderProperties;
import com.commerce.payment.provider.PaymentProviderPropertiesResolver;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;

@ExtendWith(MockitoExtension.class)
class PaymentReadyServiceTest {

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private PaymentProviderPropertiesResolver propertiesResolver;

	@Mock
	private PaymentProviderProperties providerProperties;

	@InjectMocks
	private PaymentReadyService paymentReadyService;

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

		PaymentReadyResult result = paymentReadyService.readyPayment(command);

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

		assertThatThrownBy(() -> paymentReadyService.readyPayment(command))
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

		assertThatThrownBy(() -> paymentReadyService.readyPayment(command))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException)exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_PAYMENT_NOT_ALLOWED);
			});
	}

	private Order createOrder(int totalPrice) {
		Order order = Order.create(createMember());
		Product product = Product.builder()
			.name("product")
			.price(totalPrice)
			.status(ProductStatus.ON_SALE)
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
		given(propertiesResolver.resolve(PaymentProvider.NAVERPAY)).willReturn(providerProperties);
		given(providerProperties.getClientId()).willReturn("client-id");
		given(providerProperties.getChainId()).willReturn("chain-id");
		given(providerProperties.getReturnUrl()).willReturn("https://return-url");
	}
}
