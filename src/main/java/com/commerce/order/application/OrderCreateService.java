package com.commerce.order.application;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.commerce.common.exception.CommonErrorCode;
import com.commerce.common.exception.CommonException;
import com.commerce.member.domain.Member;
import com.commerce.member.domain.repository.MemberRepository;
import com.commerce.member.exception.MemberErrorCode;
import com.commerce.member.exception.MemberException;
import com.commerce.order.application.command.OrderCreateCommand;
import com.commerce.order.application.command.OrderCreateItem;
import com.commerce.order.application.result.OrderCreateResult;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.order.application.port.OrderIdempotencyStore;
import com.commerce.product.domain.Product;
import com.commerce.product.exception.ProductErrorCode;
import com.commerce.product.exception.ProductException;
import com.commerce.product.domain.repository.ProductRepository;
import com.commerce.stock.application.StockInventoryService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderCreateService {

	private final MemberRepository memberRepository;
	private final ProductRepository productRepository;
	private final OrderRepository orderRepository;
	private final OrderIdempotencyStore orderIdempotencyStore;
	private final StockInventoryService stockInventoryService;

	@Value("${order.idempotency.ttl-seconds:600}")
	private long idempotencyTtlSeconds;

	@Transactional
	public OrderCreateResult createOrder(OrderCreateCommand command) {
		if (!StringUtils.hasText(command.getIdempotencyKey())) {
			throw new CommonException(CommonErrorCode.INVALID_REQUEST);
		}

		Long memberId = command.getMemberId();
		String idempotencyKey = command.getIdempotencyKey();
		Duration ttl = Duration.ofSeconds(idempotencyTtlSeconds);
		boolean reserved = orderIdempotencyStore.reserve(memberId, idempotencyKey, ttl);

		if (!reserved) {
			Long orderId = orderIdempotencyStore.getCompletedOrderId(memberId, idempotencyKey)
				.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_IDEMPOTENCY_IN_PROGRESS));

			Order order = orderRepository.findById(orderId)
				.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

			return OrderCreateResult.from(order);
		}

		try {
			OrderCreateResult result = createOrderWithPessimisticLockOrdered(command);
			orderIdempotencyStore.complete(memberId, idempotencyKey, result.getOrderId(), ttl);
			return result;
		} catch (RuntimeException ex) {
			orderIdempotencyStore.clear(memberId, idempotencyKey);
			throw ex;
		}
	}

	private OrderCreateResult createOrderWithPessimisticLockOrdered(OrderCreateCommand command) {
		OrderCreateCommand sortedCommand = sortItemsByProductId(command);
		return createOrderWithStockDecrease(sortedCommand);
	}

	private OrderCreateCommand sortItemsByProductId(OrderCreateCommand command) {
		List<OrderCreateItem> sortedItems = command.getItems().stream()
			.sorted(Comparator.comparing(OrderCreateItem::getProductId))
			.toList();

		return OrderCreateCommand.builder()
			.memberId(command.getMemberId())
			.idempotencyKey(command.getIdempotencyKey())
			.items(sortedItems)
			.build();
	}

	private OrderCreateResult createOrderWithStockDecrease(OrderCreateCommand command) {
		Member member = memberRepository.findById(command.getMemberId())
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

		List<Long> productIds = extractDistinctProductIds(command);
		Map<Long, Product> productsById = productRepository.findAllById(productIds).stream()
			.collect(Collectors.toMap(Product::getId, Function.identity()));
		if (productsById.size() != productIds.size()) {
			throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
		}

		Order order = Order.create(member);

		for (OrderCreateItem item : command.getItems()) {
			Product product = productsById.get(item.getProductId());
			if (product == null) {
				throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
			}

			stockInventoryService.decrease(product.getId(), item.getQuantity());
			order.addOrderItem(product, item.getQuantity());
		}

		orderRepository.save(order);

		return OrderCreateResult.from(order);
	}

	private List<Long> extractDistinctProductIds(OrderCreateCommand command) {
		return command.getItems().stream()
			.map(OrderCreateItem::getProductId)
			.distinct()
			.toList();
	}
}
