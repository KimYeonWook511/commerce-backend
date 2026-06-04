package com.commerce.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.payment.application.command.ReservePaymentCommand;
import com.commerce.payment.application.result.ReservePaymentResult;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.repository.PaymentReservationRepository;
import com.commerce.payment.provider.PaymentProviderProperties;
import com.commerce.payment.provider.PaymentProviderPropertiesResolver;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.domain.repository.ProductRepository;

@ExtendWith(MockitoExtension.class)
class ReservePaymentServiceTest {

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private ProductRepository productRepository;

	@Mock
	private PaymentReservationRepository paymentReservationRepository;

	@Mock
	private PaymentProviderPropertiesResolver propertiesResolver;

	@Mock
	private PaymentProviderProperties providerProperties;

	@InjectMocks
	private ReservePaymentService reservePaymentService;

	@DisplayName("결제 예약 요청을 하면 결제 예약 응답을 반환한다")
	@Test
	void reserve_whenOrderExists_returnReserveResponse() {
		Product product = createProduct(10L, "product", 1500);
		Order order = createOrder(product);
		setOrderId(order, 1L);
		given(orderRepository.findByIdAndMemberIdWithItems(1L, 1L)).willReturn(Optional.of(order));
		given(productRepository.findAllById(anyList())).willReturn(List.of(product));
		given(paymentReservationRepository.save(any(PaymentReservation.class)))
			.willAnswer(invocation -> invocation.getArgument(0));
		stubPaymentProperties();

		ReservePaymentCommand command = ReservePaymentCommand.builder()
			.memberId(1L)
			.orderId(1L)
			.provider(PaymentProvider.NAVERPAY)
			.build();

		ReservePaymentResult result = reservePaymentService.reserve(command);

		assertThat(result.getClientId()).isEqualTo("client-id");
		assertThat(result.getChainId()).isEqualTo("chain-id");
		assertThat(result.getMerchantPayKey()).startsWith("PAY-");
		assertThat(result.getProductName()).isEqualTo("product");
		assertThat(result.getTotalPayAmount()).isEqualTo(1500);
	}

	@DisplayName("주문이 없으면 결제 예약에 실패한다")
	@Test
	void reserve_whenOrderNotFound_throwException() {
		given(orderRepository.findByIdAndMemberIdWithItems(1L, 1L)).willReturn(Optional.empty());

		ReservePaymentCommand command = ReservePaymentCommand.builder()
			.memberId(1L)
			.orderId(1L)
			.provider(PaymentProvider.NAVERPAY)
			.build();

		assertThatThrownBy(() -> reservePaymentService.reserve(command))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException)exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);
			});
	}

	@DisplayName("결제를 진행할 수 없는 주문이면 결제 예약에 실패한다")
	@Test
	void reserve_whenOrderIsNotPayable_throwException() {
		Product product = createProduct(10L, "product", 1500);
		Order order = createOrder(product);
		setOrderId(order, 1L);
		ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);
		given(orderRepository.findByIdAndMemberIdWithItems(1L, 1L)).willReturn(Optional.of(order));

		ReservePaymentCommand command = ReservePaymentCommand.builder()
			.memberId(1L)
			.orderId(1L)
			.provider(PaymentProvider.NAVERPAY)
			.build();

		assertThatThrownBy(() -> reservePaymentService.reserve(command))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> {
				OrderException orderException = (OrderException)exception;
				assertThat(orderException.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_PAYMENT_NOT_ALLOWED);
			});
	}

	private Product createProduct(Long id, String name, int price) {
		Product product = Product.builder()
			.name(name)
			.price(price)
			.status(ProductStatus.ON_SALE)
			.build();
		ReflectionTestUtils.setField(product, "id", id);
		return product;
	}

	private Order createOrder(Product product) {
		Order order = Order.create(1L);
		order.addOrderItem(product.getId(), 1, product.getPrice());
		return order;
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
