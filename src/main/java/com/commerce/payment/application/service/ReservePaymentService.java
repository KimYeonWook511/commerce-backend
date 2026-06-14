package com.commerce.payment.application.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.common.util.UlidGenerator;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderItem;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.payment.application.command.ReservePaymentCommand;
import com.commerce.payment.application.result.ReservePaymentResult;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.domain.repository.PaymentReservationRepository;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.provider.PaymentProviderProperties;
import com.commerce.payment.provider.PaymentProviderPropertiesResolver;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.repository.ProductRepository;
import com.commerce.product.domain.exception.ProductErrorCode;
import com.commerce.product.domain.exception.ProductException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservePaymentService {

	private final OrderRepository orderRepository;
	private final ProductRepository productRepository;
	private final PaymentReservationRepository paymentReservationRepository;
	private final PaymentRepository paymentRepository;
	private final PaymentProviderPropertiesResolver propertiesResolver;

	private static final Duration PAYMENT_RESERVATION_EXPIRY = Duration.ofMinutes(30);

	@Transactional
	public ReservePaymentResult reserve(ReservePaymentCommand command) {
		Order order = orderRepository.findByIdAndMemberIdWithItems(command.getOrderId(), command.getMemberId())
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

		order.checkPayable();

		// UNKNOWN 행이 있는 주문은 결제 시도 차단 (ADR-6)
		if (paymentRepository.existsUnknownByOrderId(order.getId())) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_RESULT_PENDING);
		}

		LocalDateTime now = LocalDateTime.now();
		PaymentReservation reservation = resolveReservation(order, command, now);

		PaymentProviderProperties properties = propertiesResolver.resolve(command.getProvider());

		List<OrderItem> items = order.getOrderItems();
		int productCount = items.stream().mapToInt(OrderItem::getQuantity).sum();
		int totalPayAmount = order.getTotalPrice();

		List<Long> productIds = items.stream().map(OrderItem::getProductId).toList();
		Map<Long, Product> productsById = productRepository.findAllById(productIds).stream()
			.collect(Collectors.toMap(Product::getId, Function.identity()));

		log.info("결제 예약 완료 merchantPayKey={} orderId={} memberId={} amount={}",
			reservation.getMerchantPayKey(), order.getId(), command.getMemberId(), totalPayAmount);

		return ReservePaymentResult.builder()
			.clientId(properties.getClientId())
			.chainId(properties.getChainId())
			.merchantPayKey(reservation.getMerchantPayKey())
			.productName(buildProductName(items, productsById))
			.productCount(productCount)
			.totalPayAmount(totalPayAmount)
			.taxScopeAmount(totalPayAmount)
			.taxExScopeAmount(0)
			.returnUrl(buildReturnUrl(properties.getReturnUrl(), reservation.getMerchantPayKey()))
			.build();
	}

	// 같은 (orderId, provider)의 RESERVED 예약을 조회해 재사용/만료 판정한다.
	// 유효하면 재사용, 만료/무효(금액 변경 등)면 expire로 reservedKey를 회수한 뒤 새 예약을 발급한다.
	// 회수하지 않으면 만료 행이 reservedKey를 계속 점유해 uk_payment_reservation_reserved_key 위반으로 재예약이 막힌다.
	private PaymentReservation resolveReservation(Order order, ReservePaymentCommand command, LocalDateTime now) {
		PaymentReservation existing = paymentReservationRepository
			.findReserved(order.getId(), command.getProvider())
			.orElse(null);

		if (existing != null
			&& existing.isReusableFor(command.getMemberId(), command.getProvider(), order.getTotalPrice(), now)) {
			return existing;
		}

		if (existing != null) {
			existing.expire();
			paymentReservationRepository.save(existing);
		}

		return createNewReservation(order, command, now);
	}

	private PaymentReservation createNewReservation(Order order, ReservePaymentCommand command, LocalDateTime now) {
		String merchantPayKey = "PAY-" + UlidGenerator.generate();
		return paymentReservationRepository.save(
			PaymentReservation.createReserved(
				order.getId(),
				command.getMemberId(),
				order.getTotalPrice(),
				command.getProvider(),
				merchantPayKey,
				now.plus(PAYMENT_RESERVATION_EXPIRY)
			)
		);
	}

	private String buildProductName(List<OrderItem> items, Map<Long, Product> productsById) {
		if (items.isEmpty()) {
			throw new OrderException(OrderErrorCode.ORDER_ITEMS_EMPTY);
		}

		Product firstProduct = productsById.get(items.get(0).getProductId());
		if (firstProduct == null) {
			throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
		}
		String firstName = firstProduct.getName();
		if (items.size() == 1) {
			return firstName;
		}

		return firstName + " 외 " + (items.size() - 1) + "건";
	}

	private String buildReturnUrl(String baseUrl, String merchantPayKey) {
		return baseUrl + "?merchantPayKey=" + merchantPayKey;
	}
}
