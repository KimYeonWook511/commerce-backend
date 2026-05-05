package com.commerce.order.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.commerce.common.exception.CommonErrorCode;
import com.commerce.common.exception.CommonException;
import com.commerce.member.domain.Member;
import com.commerce.member.exception.MemberErrorCode;
import com.commerce.member.exception.MemberException;
import com.commerce.member.repository.MemberRepository;
import com.commerce.order.domain.Order;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.order.redis.OrderIdempotencyStore;
import com.commerce.order.repository.OrderRepository;
import com.commerce.order.service.command.OrderCreateItem;
import com.commerce.order.service.command.OrderCreateCommand;
import com.commerce.order.service.result.OrderCancelResult;
import com.commerce.order.service.result.OrderCreateResult;
import com.commerce.orderitem.domain.OrderItem;
import com.commerce.outbox.service.OutboxService;
import com.commerce.outbox.stock.service.command.StockRestoreOutboxCreateCommand;
import com.commerce.product.domain.Product;
import com.commerce.product.exception.ProductErrorCode;
import com.commerce.product.exception.ProductException;
import com.commerce.product.repository.ProductRepository;
import com.commerce.stock.application.OrderStockService;
import com.commerce.stock.application.StockConcurrencyService;
import com.commerce.stock.application.command.StockDecreaseBatchCommand;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

	private final MemberRepository memberRepository;
	private final ProductRepository productRepository;
	private final OrderRepository orderRepository;
	private final OrderIdempotencyStore orderIdempotencyStore;
	private final OrderStockService orderStockService;
	private final StockConcurrencyService stockConcurrencyService;
	private final OutboxService outboxService;

	@Value("${order.idempotency.ttl-seconds:600}")
	private long idempotencyTtlSeconds;

	@Transactional
	public OrderCreateResult createOrder(OrderCreateCommand command) {
		// 멱등키 검증
		if (!StringUtils.hasText(command.getIdempotencyKey())) {
			throw new CommonException(CommonErrorCode.INVALID_REQUEST);
		}

		// 멱등키 상태 등록 (lock 처럼 선점)
		Long memberId = command.getMemberId();
		String idempotencyKey = command.getIdempotencyKey();
		Duration ttl = Duration.ofSeconds(idempotencyTtlSeconds);
		boolean reserved = orderIdempotencyStore.reserve(memberId, idempotencyKey, ttl);

		// 이미 선점된 멱등키 처리
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
			// 선점한 멱등키 삭제 (PROCESSING 상태 삭제. -> FAILED로 바꾸는 것과 비교한다면??)
			orderIdempotencyStore.clear(memberId, idempotencyKey);
			throw ex;
		}
	}

	@Transactional
	public OrderCancelResult cancelOrder(Long memberId, Long orderId) {
		try {
			// fetch join이 많음! -> 데이터가 커지면 최적화가 필요할 수 있음
			Order order = orderRepository.findByIdAndMemberIdWithItems(orderId, memberId)
				.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

			order.cancel();

			// 데드락 방지를 위한 정렬
			List<OrderItem> sortedList = order.getOrderItems().stream()
				.sorted(Comparator.comparing(item -> item.getProduct().getId()))
				.toList();

			// 비관적 락을 이용하여 재고 수량 복구
			sortedList.forEach(item ->
					orderStockService.increaseWithPessimisticLock(item.getProduct().getId(), item.getQuantity())
			);

			// OrderException의 형태로 바꾸기 위한 try-catch and flush()
			// TransactionTemplate을 사용하는 걸로 바꿀 수도 있음
			orderRepository.flush();

			return OrderCancelResult.from(order);
		} catch (OptimisticLockingFailureException ex) {
			throw new OrderException(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED);
		}
	}

	public Order getOrderByMerchantPayKeyAndMemberId(String merchantPayKey, Long memberId) {
		return orderRepository.findByMerchantPayKeyAndMemberId(merchantPayKey, memberId)
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));
	}

	@Transactional
	public OrderCreateResult createOrderWithoutLock(OrderCreateCommand command) {
		return createOrderWithStockDecrease(command, stockConcurrencyService::decrease);
	}

	@Transactional
	public OrderCreateResult createOrderWithSynchronized(OrderCreateCommand command) {
		return createOrderWithStockDecrease(command, stockConcurrencyService::decreaseWithSynchronized);
	}

	@Transactional
	public OrderCreateResult createOrderWithSynchronizedAndTransaction(OrderCreateCommand command) {
		return createOrderWithStockDecrease(command, stockConcurrencyService::decreaseWithSynchronizedAndTransaction);
	}

	@Transactional
	public OrderCreateResult createOrderWithReentrantLockAndTransaction(OrderCreateCommand command) {
		return createOrderWithStockDecrease(command, stockConcurrencyService::decreaseWithReentrantLockAndTransaction);
	}

	@Transactional
	public OrderCreateResult createOrderWithOptimisticLock(OrderCreateCommand command) {
		return createOrderWithStockDecrease(command, stockConcurrencyService::decreaseWithOptimisticLock);
	}

	@Transactional
	public OrderCreateResult createOrderWithPessimisticLock(OrderCreateCommand command) {
		return createOrderWithStockDecrease(command, orderStockService::decreaseWithPessimisticLock);
	}

	@Transactional
	public OrderCreateResult createOrderWithPessimisticLockOrdered(OrderCreateCommand command) {
		OrderCreateCommand sortedRequest = sortItemsByProductId(command);
		return createOrderWithStockDecrease(sortedRequest, orderStockService::decreaseWithPessimisticLock);
	}

	@Transactional
	public OrderCreateResult createOrderWithPessimisticLockBatch(OrderCreateCommand command) {
		Member member = memberRepository.findById(command.getMemberId())
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

		Map<Long, Integer> quantitiesByProductId = mergeQuantities(command);
		List<Long> productIds = quantitiesByProductId.keySet().stream()
			// .sorted() // in절에 쓰이는 list는 정렬해도 락 순서를 보장하지 않음
			.toList();

		Map<Long, Product> findProducts = productRepository.findAllById(productIds).stream()
			.collect(Collectors.toMap(Product::getId, Function.identity()));
		if (findProducts.size() != productIds.size()) {
			throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
		}

		orderStockService.decreaseBatchWithPessimisticLock(
			StockDecreaseBatchCommand.from(quantitiesByProductId)
		);

		Order order = Order.create(member);
		for (OrderCreateItem item : command.getItems()) {
			Product product = findProducts.get(item.getProductId());
			if (product == null) {
				throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
			}
			order.addOrderItem(product, item.getQuantity());
		}

		orderRepository.save(order);

		return OrderCreateResult.from(order);
	}

	private OrderCreateCommand sortItemsByProductId(OrderCreateCommand command) {
		List<OrderCreateItem> sortedItems = command.getItems().stream()
			.sorted(Comparator.comparing(OrderCreateItem::getProductId))
			.toList();

		return OrderCreateCommand.builder()
			.memberId(command.getMemberId())
			.items(sortedItems)
			.build();
	}

	private Map<Long, Integer> mergeQuantities(OrderCreateCommand command) {
		Map<Long, Integer> quantities = new HashMap<>();
		for (OrderCreateItem item : command.getItems()) {
			quantities.merge(item.getProductId(), item.getQuantity(), Integer::sum);
		}
		return quantities;
	}

	/**
	 * 	주문 만료 배치에서 사용하는 메서드
	 * 	트랜잭션은 청크 단위임
	 */
	@Transactional
	public void expireOrder(Long orderId) {
		Order order = orderRepository.findByIdWithItems(orderId)
			.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

		// 주문 취소 처리
		order.cancel();

		// 이벤트 발행
		LocalDateTime now = LocalDateTime.now();
		outboxService.createStockRestoreOutboxEvent(toStockRestoreOutboxCreateCommand(order, now));
	}

	private OrderCreateResult createOrderWithStockDecrease(
		OrderCreateCommand command,
		BiConsumer<Long, Integer> stockDecrease
	) {
		// 회원 조회
		Member member = memberRepository.findById(command.getMemberId())
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

		Order order = Order.create(member);

		for (OrderCreateItem item : command.getItems()) {
			// 상품 조회
			Product product = productRepository.findById(item.getProductId())
				.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));

			// 재고 감소
			stockDecrease.accept(product.getId(), item.getQuantity());

			order.addOrderItem(product, item.getQuantity());
		}

		orderRepository.save(order);

		return OrderCreateResult.from(order);
	}

	private StockRestoreOutboxCreateCommand toStockRestoreOutboxCreateCommand(Order order, LocalDateTime requestedAt) {
		Map<Long, Integer> mergedQuantities = new HashMap<>();
		for (OrderItem orderItem : order.getOrderItems()) {
			Long productId = orderItem.getProduct().getId();
			mergedQuantities.merge(productId, orderItem.getQuantity(), Integer::sum);
		}

		List<StockRestoreOutboxCreateCommand.Item> items = mergedQuantities.entrySet().stream()
			.map(entry -> StockRestoreOutboxCreateCommand.Item.builder()
				.productId(entry.getKey())
				.quantity(entry.getValue())
				.build())
			.sorted(Comparator.comparing(StockRestoreOutboxCreateCommand.Item::getProductId))
			.toList();

		return StockRestoreOutboxCreateCommand.builder()
			.orderId(order.getId())
			.items(items)
			.requestedAt(requestedAt)
			.build();
	}
}
