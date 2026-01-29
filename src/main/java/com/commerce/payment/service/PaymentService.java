package com.commerce.payment.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.order.domain.Order;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.order.repository.OrderRepository;
import com.commerce.orderitem.domain.OrderItem;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.provider.PaymentProviderProperties;
import com.commerce.payment.provider.PaymentProviderPropertiesResolver;
import com.commerce.payment.repository.PaymentRepository;
import com.commerce.payment.service.request.PaymentReadyServiceRequest;
import com.commerce.payment.service.response.PaymentReadyResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

	private final OrderRepository orderRepository;
	private final PaymentRepository paymentRepository;
	private final PaymentProviderPropertiesResolver propertiesResolver;

	@Transactional
	public PaymentReadyResponse readyPayment(PaymentReadyServiceRequest request) {
		Order order = orderRepository.findByIdAndMemberIdWithItems(request.getOrderId(), request.getMemberId())
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

		// 기존 결제 정보 가져오기 or 결제 생성하기
		Payment payment = paymentRepository.findByOrderId(order.getId())
			.orElseGet(() -> paymentRepository.save(Payment.create(order, order.getTotalPrice(), request.getProvider())));

		// 결제 수단에 맞는 프로퍼티 가져오기
		PaymentProviderProperties properties = propertiesResolver.resolve(request.getProvider());

		List<OrderItem> items = order.getOrderItems();
		int productCount = items.stream().mapToInt(OrderItem::getQuantity).sum();
		int totalPayAmount = order.getTotalPrice();

		// Response 변환을 from으로 하면 결합도가 올라가는 것 같기도?
		return PaymentReadyResponse.builder()
			.clientId(properties.getClientId())
			.chainId(properties.getChainId())
			.merchantPayKey(payment.getMerchantPayKey())
			.productName(buildProductName(items))
			.productCount(productCount)
			.totalPayAmount(totalPayAmount)
			.taxScopeAmount(totalPayAmount)
			.taxExScopeAmount(0)
			.returnUrl(properties.getReturnUrl())
			.build();
	}

	public Payment getPayment(Long orderId) {
		return paymentRepository.findByOrderId(orderId)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));
	}

	@Transactional
	public Payment completePayment(Long orderId, LocalDateTime approvedAt) {
		Payment payment = getPayment(orderId);
		payment.complete(approvedAt);
		return payment;
	}

	@Transactional
	public Payment failPayment(Long orderId, String reason) {
		Payment payment = getPayment(orderId);
		payment.fail(reason);
		return payment;
	}

	@Transactional
	public Payment cancelPayment(Long orderId, String reason) {
		Payment payment = getPayment(orderId);
		payment.cancel(reason);
		return payment;
	}

	private String buildProductName(List<OrderItem> items) {
		if (items.isEmpty()) {
			throw new OrderException(OrderErrorCode.ORDER_ITEMS_EMPTY);
		}

		String firstName = items.get(0).getProduct().getName();
		if (items.size() == 1) {
			return firstName;
		}

		return firstName + " 외 " + (items.size() - 1) + "건";
	}
}
