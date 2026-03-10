package com.commerce.payment.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.common.util.UlidGenerator;
import com.commerce.order.domain.Order;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.order.repository.OrderRepository;
import com.commerce.orderitem.domain.OrderItem;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.provider.PaymentProviderProperties;
import com.commerce.payment.provider.PaymentProviderPropertiesResolver;
import com.commerce.payment.repository.PaymentRepository;
import com.commerce.payment.service.command.PaymentReadyCommand;
import com.commerce.payment.service.result.PaymentReadyResult;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

	private final OrderRepository orderRepository;
	private final PaymentRepository paymentRepository;
	private final PaymentAttemptService paymentAttemptService;
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

	public Payment findPaymentByMerchantPayKeyOrNull(String merchantPayKey) {
		return paymentRepository.findByMerchantPayKey(merchantPayKey).orElse(null);
	}

	@Transactional
	public Payment completeApprove(
		String merchantPayKey,
		PaymentProvider provider,
		String pgPaymentId,
		LocalDateTime approvedAt
	) {
		// 동일 주문은 같은 순서로 잠가서 주문/결제 락 경합을 줄인다.
		Order order = orderRepository.findByMerchantPayKeyForUpdate(merchantPayKey)
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

		// 이미 해당 결제로 성공한 payment가 있는지 확인 -> 해당 부분이 없으면 try-catch에서 failed처리가 되어버림
		Payment completedPayment = paymentRepository.findByMerchantPayKey(merchantPayKey)
			.map(payment -> validateCompletedPaymentOrThrow(payment, provider, pgPaymentId))
			.orElse(null);

		// 결제 시도 완료 처리
		paymentAttemptService.succeedApproveAttempt(merchantPayKey, provider, pgPaymentId, approvedAt);

		if (completedPayment != null) {
			return completedPayment; // 이미 해당 결제로 성공한 payment 반환
		}

		// 주문 결제 완료 처리
		order.completePayment();

		try {
			// 결제 최종 정보 저장
			return paymentRepository.saveAndFlush(
				Payment.createCompleted(order, provider, merchantPayKey, pgPaymentId, approvedAt)
			);
		} catch (DataIntegrityViolationException ex) {
			// 저장하는 사이 payment가 생성됨
			throw new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE);
		}
	}

	private Payment validateCompletedPaymentOrThrow(
		Payment payment,
		PaymentProvider provider,
		String pgPaymentId
	) {
		if (payment.getProvider() != provider) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE);
		}
		if (!pgPaymentId.equals(payment.getPgPaymentId())) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE);
		}
		if (payment.getStatus() != PaymentStatus.COMPLETED) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_STATUS_NOT_ALLOWED);
		}
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

	private String buildReturnUrl(String baseUrl, String merchantPayKey) {
		return baseUrl + "?merchantPayKey=" + merchantPayKey;
	}
}
