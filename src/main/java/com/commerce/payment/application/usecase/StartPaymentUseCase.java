package com.commerce.payment.application.usecase;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.commerce.common.exception.CommonErrorCode;
import com.commerce.common.exception.CommonException;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderItem;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.payment.application.dto.StartPaymentCommand;
import com.commerce.payment.application.dto.StartPaymentResult;
import com.commerce.payment.application.port.PaymentIdempotencyStore;
import com.commerce.payment.application.port.PaymentProviderConfigReader;
import com.commerce.payment.application.port.dto.PaymentProviderConfig;
import com.commerce.payment.application.service.PaymentService;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.exception.PaymentErrorCode;
import com.commerce.payment.domain.exception.PaymentException;
import com.commerce.payment.domain.repository.PaymentRepository;
import com.commerce.payment.infrastructure.PaymentIdempotencyStoreUnavailableException;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.exception.ProductErrorCode;
import com.commerce.product.domain.exception.ProductException;
import com.commerce.product.domain.repository.ProductRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제를 시작한다. 선점 → 결제 생성 → 결제창 정보 반환 순서다.
 *
 * <p>결제 행이 결제창을 띄우기 전에 먼저 생기고, 그 행이 활성 슬롯을 잡아 이중결제를 막는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartPaymentUseCase {

	private final OrderRepository orderRepository;
	private final ProductRepository productRepository;
	private final PaymentRepository paymentRepository;
	private final PaymentIdempotencyStore paymentIdempotencyStore;
	private final PaymentProviderConfigReader paymentProviderConfigReader;
	private final PaymentService paymentService;

	@Value("${payment.idempotency.ttl-seconds:60}")
	private long idempotencyTtlSeconds;

	public StartPaymentResult start(StartPaymentCommand command) {
		if (!StringUtils.hasText(command.idempotencyKey())) {
			throw new CommonException(CommonErrorCode.INVALID_REQUEST);
		}

		Long memberId = command.memberId();
		String idempotencyKey = command.idempotencyKey();
		Duration ttl = Duration.ofSeconds(idempotencyTtlSeconds);

		boolean reserved;
		try {
			reserved = paymentIdempotencyStore.reserve(memberId, idempotencyKey, ttl);
		} catch (PaymentIdempotencyStoreUnavailableException e) {
			// 선점 저장소가 죽으면 DB 유일 제약 경로로 물러난다. 표시를 만들지 못했으므로 해제하지 않는다.
			log.warn("결제 멱등 선점 저장소 장애, DB 유일 제약으로 물러난다: memberId={}, key={}", memberId, idempotencyKey);
			return findOrStart(command);
		}

		if (!reserved) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_REQUEST_IN_PROGRESS);
		}

		try {
			return findOrStart(command);
		} finally {
			// 트랜잭션을 열지 않는 계층이라 이 finally 는 결제 트랜잭션이 끝난 뒤에 돈다.
			// 빠뜨리면 만료될 때까지 그 회원의 그 키가 막힌다.
			paymentIdempotencyStore.clear(memberId, idempotencyKey);
		}
	}

	private StartPaymentResult findOrStart(StartPaymentCommand command) {
		// 그 회원의 주문만 찾는다. 남의 주문 번호를 실으면 그 주문이 있는지조차 드러나지 않는다.
		Order order = orderRepository.findByIdAndMemberIdWithItems(command.orderId(), command.memberId())
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

		// 선점을 잡은 뒤에 조회한다 — 선점이 만료된 뒤 온 정당한 재요청을 여기서 흡수한다.
		Optional<Payment> existing = paymentRepository.findByMemberIdAndIdempotencyKey(
			command.memberId(), command.idempotencyKey());
		if (existing.isPresent()) {
			Payment payment = existing.get();
			payment.requireSameRequest(order.getId(), order.getTotalPrice());
			log.info("결제 시작 멱등 응답 paymentKey={} orderId={} memberId={}",
				payment.getPaymentKey(), order.getId(), command.memberId());
			return buildResult(order, payment);
		}

		order.checkPayable();
		return buildResult(order, createPayment(command, order));
	}

	/**
	 * 유일 제약이 선점 층의 안전망이다. 부딪혔다는 것은 같은 자리를 다른 요청이 먼저 잡았다는 뜻이라
	 * 회원이 할 일이 같고, 그래서 서버 오류로 내보내지 않는다.
	 *
	 * <p>그 판정은 제약 이름을 볼 수 있는 persistence adapter가 하고 여기까지 도메인 예외로 온다.
	 * 필수값 누락 같은 다른 무결성 위반은 번역되지 않고 안전망으로 가므로 이 자리에서 잡지 않는다.
	 */
	private Payment createPayment(StartPaymentCommand command, Order order) {
		return paymentService.start(
			order.getId(), command.memberId(), command.pg(), command.idempotencyKey(), order.getTotalPrice());
	}

	private StartPaymentResult buildResult(Order order, Payment payment) {
		PaymentProviderConfig config = paymentProviderConfigReader.read(payment.getPg());
		List<OrderItem> items = order.getOrderItems();
		int productCount = items.stream().mapToInt(OrderItem::getQuantity).sum();
		int totalPayAmount = payment.getAmount();

		List<Long> productIds = items.stream().map(OrderItem::getProductId).toList();
		Map<Long, Product> productsById = productRepository.findAllById(productIds).stream()
			.collect(Collectors.toMap(Product::getId, Function.identity()));

		return new StartPaymentResult(
			config.clientId(),
			config.chainId(),
			payment.getPaymentKey(),
			buildProductName(items, productsById),
			productCount,
			totalPayAmount,
			totalPayAmount,
			0,
			buildReturnUrl(config.returnUrl(), payment.getPaymentKey())
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
		if (items.size() == 1) {
			return firstProduct.getName();
		}
		return firstProduct.getName() + " 외 " + (items.size() - 1) + "건";
	}

	private String buildReturnUrl(String baseUrl, String paymentKey) {
		return baseUrl + "?merchantPayKey=" + paymentKey;
	}
}
