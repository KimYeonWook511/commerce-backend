package com.commerce.payment.application;

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
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.payment.application.command.ReservePaymentCommand;
import com.commerce.payment.application.result.ReservePaymentResult;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.repository.PaymentReservationRepository;
import com.commerce.payment.provider.PaymentProviderProperties;
import com.commerce.payment.provider.PaymentProviderPropertiesResolver;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.repository.ProductRepository;
import com.commerce.product.exception.ProductErrorCode;
import com.commerce.product.exception.ProductException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservePaymentService {

	private final OrderRepository orderRepository;
	private final ProductRepository productRepository;
	private final PaymentReservationRepository paymentReservationRepository;
	private final PaymentProviderPropertiesResolver propertiesResolver;

	private static final int RESERVATION_EXPIRE_MINUTES = 30;

	@Transactional
	public ReservePaymentResult reserve(ReservePaymentCommand command) {
		Order order = orderRepository.findByIdAndMemberIdWithItems(command.getOrderId(), command.getMemberId())
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

		order.checkPayable();

		PaymentProviderProperties properties = propertiesResolver.resolve(command.getProvider());

		List<OrderItem> items = order.getOrderItems();
		int productCount = items.stream().mapToInt(OrderItem::getQuantity).sum();
		int totalPayAmount = order.getTotalPrice();

		List<Long> productIds = items.stream().map(OrderItem::getProductId).toList();
		Map<Long, Product> productsById = productRepository.findAllById(productIds).stream()
			.collect(Collectors.toMap(Product::getId, Function.identity()));

		String merchantPayKey = "PAY-" + UlidGenerator.generate();
		LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(RESERVATION_EXPIRE_MINUTES);

		PaymentReservation reservation = paymentReservationRepository.save(
			PaymentReservation.createReserved(
				order.getId(),
				command.getMemberId(),
				totalPayAmount,
				command.getProvider(),
				merchantPayKey,
				expiresAt
			)
		);

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
