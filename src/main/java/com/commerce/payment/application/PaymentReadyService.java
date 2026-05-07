package com.commerce.payment.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.common.util.UlidGenerator;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.domain.Order;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.order.domain.OrderItem;
import com.commerce.payment.application.command.PaymentReadyCommand;
import com.commerce.payment.application.result.PaymentReadyResult;
import com.commerce.payment.provider.PaymentProviderProperties;
import com.commerce.payment.provider.PaymentProviderPropertiesResolver;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentReadyService {

	private final OrderRepository orderRepository;
	private final PaymentProviderPropertiesResolver propertiesResolver;

	@Transactional
	public PaymentReadyResult readyPayment(PaymentReadyCommand command) {
		Order order = orderRepository.findByIdAndMemberIdWithItems(command.getOrderId(), command.getMemberId())
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

		// 주문 상태 확인
		order.checkPayable();

		// 주문 결제 키 생성
		if (order.getMerchantPayKey() == null) {
			order.assignMerchantPayKey("PAY-" + UlidGenerator.generate());
		}

		// 결제 수단에 맞는 프로퍼티 가져오기
		PaymentProviderProperties properties = propertiesResolver.resolve(command.getProvider());

		List<OrderItem> items = order.getOrderItems();
		int productCount = items.stream().mapToInt(OrderItem::getQuantity).sum();
		int totalPayAmount = order.getTotalPrice();

		return PaymentReadyResult.builder()
			.clientId(properties.getClientId())
			.chainId(properties.getChainId())
			.merchantPayKey(order.getMerchantPayKey())
			.productName(buildProductName(items))
			.productCount(productCount)
			.totalPayAmount(totalPayAmount)
			.taxScopeAmount(totalPayAmount)
			.taxExScopeAmount(0)
			.returnUrl(buildReturnUrl(properties.getReturnUrl(), order.getMerchantPayKey()))
			.build();
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

	private String buildReturnUrl(String baseUrl, String merchantPayKey) {
		return baseUrl + "?merchantPayKey=" + merchantPayKey;
	}
}
