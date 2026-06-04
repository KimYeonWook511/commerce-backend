package com.commerce.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.LocalDateTime;
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
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.repository.PaymentReservationRepository;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
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
	private PaymentRepository paymentRepository;

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
		given(paymentReservationRepository.findReusable(anyLong(), anyLong(), any(), anyInt(), any(LocalDateTime.class)))
			.willReturn(Optional.empty());
		given(paymentReservationRepository.save(any(PaymentReservation.class)))
			.willAnswer(invocation -> invocation.getArgument(0));
		given(productRepository.findAllById(anyList())).willReturn(List.of(product));
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

	@DisplayName("재사용 가능한 예약이 있으면 새로 발급하지 않고 기존 예약의 merchantPayKey를 반환한다")
	@Test
	void reserve_whenReusableReservationExists_reusesIt() {
		// given
		Product product = createProduct(10L, "product", 1500);
		Order order = createOrder(product);
		setOrderId(order, 1L);

		PaymentReservation existingReservation = PaymentReservation.createReserved(
			1L, 1L, 1500, PaymentProvider.NAVERPAY, "PAY-EXISTING", LocalDateTime.now().plusMinutes(15));

		given(orderRepository.findByIdAndMemberIdWithItems(1L, 1L)).willReturn(Optional.of(order));
		given(paymentReservationRepository.findReusable(
			eq(1L), eq(1L), eq(PaymentProvider.NAVERPAY), eq(1500), any(LocalDateTime.class)))
			.willReturn(Optional.of(existingReservation));
		given(productRepository.findAllById(anyList())).willReturn(List.of(product));
		stubPaymentProperties();

		ReservePaymentCommand command = ReservePaymentCommand.builder()
			.memberId(1L)
			.orderId(1L)
			.provider(PaymentProvider.NAVERPAY)
			.build();

		// when
		ReservePaymentResult result = reservePaymentService.reserve(command);

		// then
		assertThat(result.getMerchantPayKey()).isEqualTo("PAY-EXISTING");
		then(paymentReservationRepository).should(never()).save(any());
	}

	@DisplayName("재사용 가능한 예약이 없으면 새 예약을 생성한다")
	@Test
	void reserve_whenNoReusable_createsNewReservation() {
		// given
		Product product = createProduct(10L, "product", 1500);
		Order order = createOrder(product);
		setOrderId(order, 1L);

		given(orderRepository.findByIdAndMemberIdWithItems(1L, 1L)).willReturn(Optional.of(order));
		given(paymentReservationRepository.findReusable(anyLong(), anyLong(), any(), anyInt(), any(LocalDateTime.class)))
			.willReturn(Optional.empty());
		given(paymentReservationRepository.save(any(PaymentReservation.class)))
			.willAnswer(invocation -> invocation.getArgument(0));
		given(productRepository.findAllById(anyList())).willReturn(List.of(product));
		stubPaymentProperties();

		ReservePaymentCommand command = ReservePaymentCommand.builder()
			.memberId(1L)
			.orderId(1L)
			.provider(PaymentProvider.NAVERPAY)
			.build();

		// when
		ReservePaymentResult result = reservePaymentService.reserve(command);

		// then
		then(paymentReservationRepository).should(times(1)).save(any());
		assertThat(result.getMerchantPayKey()).startsWith("PAY-");
	}

	@DisplayName("금액이 변경되면 기존 RESERVED 행을 그대로 두고 새 예약을 발급한다")
	@Test
	void reserve_whenAmountChanged_createsNewReservation() {
		// given: order.totalPrice = 2000 (이전 예약은 1500이었지만 findReusable이 amount 조건 불일치로 빈 결과 반환)
		Product product = createProduct(10L, "product", 2000);
		Order order = createOrder(product);
		setOrderId(order, 1L);

		given(orderRepository.findByIdAndMemberIdWithItems(1L, 1L)).willReturn(Optional.of(order));
		given(paymentReservationRepository.findReusable(
			eq(1L), eq(1L), eq(PaymentProvider.NAVERPAY), eq(2000), any(LocalDateTime.class)))
			.willReturn(Optional.empty());
		given(paymentReservationRepository.save(any(PaymentReservation.class)))
			.willAnswer(invocation -> invocation.getArgument(0));
		given(productRepository.findAllById(anyList())).willReturn(List.of(product));
		stubPaymentProperties();

		ReservePaymentCommand command = ReservePaymentCommand.builder()
			.memberId(1L)
			.orderId(1L)
			.provider(PaymentProvider.NAVERPAY)
			.build();

		// when
		ReservePaymentResult result = reservePaymentService.reserve(command);

		// then: 새 예약 생성, 기존 RESERVED 행은 그대로 (expire/markExpired 호출 없음)
		then(paymentReservationRepository).should(times(1)).save(any());
		assertThat(result.getMerchantPayKey()).startsWith("PAY-");
		assertThat(result.getTotalPayAmount()).isEqualTo(2000);
	}

	@DisplayName("다른 provider로 reserve하면 새 예약을 발급한다")
	@Test
	void reserve_whenDifferentProvider_createsNewReservation() {
		// given: KAKAOPAY로 요청 (이전 NAVERPAY 예약과 별개)
		Product product = createProduct(10L, "product", 1500);
		Order order = createOrder(product);
		setOrderId(order, 1L);

		given(orderRepository.findByIdAndMemberIdWithItems(1L, 1L)).willReturn(Optional.of(order));
		given(paymentReservationRepository.findReusable(
			eq(1L), eq(1L), eq(PaymentProvider.KAKAOPAY), eq(1500), any(LocalDateTime.class)))
			.willReturn(Optional.empty());
		given(paymentReservationRepository.save(any(PaymentReservation.class)))
			.willAnswer(invocation -> invocation.getArgument(0));
		given(productRepository.findAllById(anyList())).willReturn(List.of(product));
		given(propertiesResolver.resolve(PaymentProvider.KAKAOPAY)).willReturn(providerProperties);
		given(providerProperties.getClientId()).willReturn("client-id");
		given(providerProperties.getChainId()).willReturn("chain-id");
		given(providerProperties.getReturnUrl()).willReturn("https://return-url");

		ReservePaymentCommand command = ReservePaymentCommand.builder()
			.memberId(1L)
			.orderId(1L)
			.provider(PaymentProvider.KAKAOPAY)
			.build();

		// when
		ReservePaymentResult result = reservePaymentService.reserve(command);

		// then
		then(paymentReservationRepository).should(times(1)).save(any());
		assertThat(result.getMerchantPayKey()).startsWith("PAY-");
	}

	@DisplayName("UNKNOWN 상태 Payment가 있는 주문은 reserve 진입 시 PAYMENT_RESULT_PENDING을 던진다")
	@Test
	void reserve_whenUnknownByOrderId_throwsPaymentResultPending() {
		// given
		Product product = createProduct(10L, "product", 1500);
		Order order = createOrder(product);
		setOrderId(order, 1L);
		given(orderRepository.findByIdAndMemberIdWithItems(1L, 1L)).willReturn(Optional.of(order));
		given(paymentRepository.existsUnknownByOrderId(1L)).willReturn(true);

		ReservePaymentCommand command = ReservePaymentCommand.builder()
			.memberId(1L)
			.orderId(1L)
			.provider(PaymentProvider.NAVERPAY)
			.build();

		// when & then
		assertThatThrownBy(() -> reservePaymentService.reserve(command))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_RESULT_PENDING);
			});
		then(paymentReservationRepository).should(never()).save(any());
	}

	@DisplayName("같은 조건으로 순차 두 번 호출하면 두 번째 호출에서 save 없이 동일한 merchantPayKey를 반환한다")
	@Test
	void reserve_whenCalledTwiceSequentially_returnsSameMerchantPayKey() {
		// given
		Product product = createProduct(10L, "product", 1500);
		Order order = createOrder(product);
		setOrderId(order, 1L);

		PaymentReservation savedReservation = PaymentReservation.createReserved(
			1L, 1L, 1500, PaymentProvider.NAVERPAY, "PAY-REUSED", LocalDateTime.now().plusMinutes(30));

		given(orderRepository.findByIdAndMemberIdWithItems(1L, 1L)).willReturn(Optional.of(order));
		given(paymentReservationRepository.findReusable(
			eq(1L), eq(1L), eq(PaymentProvider.NAVERPAY), eq(1500), any(LocalDateTime.class)))
			.willReturn(Optional.empty())       // 첫 번째 호출: 재사용 없음
			.willReturn(Optional.of(savedReservation)); // 두 번째 호출: 재사용 가능
		given(paymentReservationRepository.save(any(PaymentReservation.class)))
			.willReturn(savedReservation);
		given(productRepository.findAllById(anyList())).willReturn(List.of(product));
		stubPaymentProperties();

		ReservePaymentCommand command = ReservePaymentCommand.builder()
			.memberId(1L)
			.orderId(1L)
			.provider(PaymentProvider.NAVERPAY)
			.build();

		// when
		ReservePaymentResult result1 = reservePaymentService.reserve(command);
		ReservePaymentResult result2 = reservePaymentService.reserve(command);

		// then: 두 번 다 같은 merchantPayKey, save는 첫 번째 호출에서만
		assertThat(result1.getMerchantPayKey()).isEqualTo(result2.getMerchantPayKey());
		then(paymentReservationRepository).should(times(1)).save(any());
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
