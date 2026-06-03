package com.commerce.payment.application;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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
public class PaymentReadyService {

	private final OrderRepository orderRepository;
	private final ProductRepository productRepository;
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

		List<Long> productIds = items.stream().map(OrderItem::getProductId).toList();
		Map<Long, Product> productsById = productRepository.findAllById(productIds).stream()
			.collect(Collectors.toMap(Product::getId, Function.identity()));

		log.info("결제 준비 완료 merchantPayKey={} orderId={} memberId={} amount={}",
			order.getMerchantPayKey(), order.getId(), command.getMemberId(), totalPayAmount);

		return PaymentReadyResult.builder()
			.clientId(properties.getClientId())
			.chainId(properties.getChainId())
			.merchantPayKey(order.getMerchantPayKey())
			.productName(buildProductName(items, productsById))
			.productCount(productCount)
			.totalPayAmount(totalPayAmount)
			.taxScopeAmount(totalPayAmount)
			.taxExScopeAmount(0)
			.returnUrl(buildReturnUrl(properties.getReturnUrl(), order.getMerchantPayKey()))
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
