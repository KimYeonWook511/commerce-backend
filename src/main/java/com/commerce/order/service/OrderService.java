package com.commerce.order.service;

import java.time.Duration;
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
import com.commerce.order.service.request.OrderCreateItem;
import com.commerce.order.service.request.OrderCreateServiceRequest;
import com.commerce.order.service.response.OrderCancelResponse;
import com.commerce.order.service.response.OrderCreateResponse;
import com.commerce.orderitem.domain.OrderItem;
import com.commerce.product.domain.Product;
import com.commerce.product.exception.ProductErrorCode;
import com.commerce.product.exception.ProductException;
import com.commerce.product.repository.ProductRepository;
import com.commerce.stock.service.StockService;
import com.commerce.stock.service.request.StockDecreaseBatchServiceRequest;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

	private final MemberRepository memberRepository;
	private final ProductRepository productRepository;
	private final OrderRepository orderRepository;
	private final OrderIdempotencyStore orderIdempotencyStore;
	private final StockService stockService;

	@Value("${order.idempotency.ttl-seconds:600}")
	private long idempotencyTtlSeconds;

	@Transactional
	public OrderCreateResponse createOrder(OrderCreateServiceRequest request) {
		// 멱등키 검증
		if (!StringUtils.hasText(request.getIdempotencyKey())) {
			throw new CommonException(CommonErrorCode.INVALID_REQUEST);
		}

		// 멱등키 상태 등록 (lock 처럼 선점)
		Long memberId = request.getMemberId();
		String idempotencyKey = request.getIdempotencyKey();
		Duration ttl = Duration.ofSeconds(idempotencyTtlSeconds);
		boolean reserved = orderIdempotencyStore.reserve(memberId, idempotencyKey, ttl);

		// 이미 선점된 멱등키 처리
		if (!reserved) {
			Long orderId = orderIdempotencyStore.getCompletedOrderId(memberId, idempotencyKey)
				.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_IDEMPOTENCY_IN_PROGRESS));

			Order order = orderRepository.findById(orderId)
				.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

			return OrderCreateResponse.from(order);
		}

		try {
			OrderCreateResponse response = createOrderWithPessimisticLockOrdered(request);
			orderIdempotencyStore.complete(memberId, idempotencyKey, response.getOrderId(), ttl);
			return response;
		} catch (RuntimeException ex) {
			// 선점한 멱등키 삭제 (PROCESSING 상태 삭제. -> FAILED로 바꾸는 것과 비교한다면??)
			orderIdempotencyStore.clear(memberId, idempotencyKey);
			throw ex;
		}
	}

	@Transactional
	public OrderCancelResponse cancelOrder(Long memberId, Long orderId) {
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
				stockService.increaseWithPessimisticLock(item.getProduct().getId(), item.getQuantity())
			);

			// OrderException의 형태로 바꾸기 위한 try-catch and flush()
			// TransactionTemplate을 사용하는 걸로 바꿀 수도 있음
			orderRepository.flush();

			return OrderCancelResponse.from(order);
		} catch (OptimisticLockingFailureException ex) {
			throw new OrderException(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED);
		}
	}

	@Transactional
	public OrderCreateResponse createOrderWithoutLock(OrderCreateServiceRequest request) {
		return createOrderWithStockDecrease(request, stockService::decrease);
	}

	@Transactional
	public OrderCreateResponse createOrderWithSynchronized(OrderCreateServiceRequest request) {
		return createOrderWithStockDecrease(request, stockService::decreaseWithSynchronized);
	}

	@Transactional
	public OrderCreateResponse createOrderWithSynchronizedAndTransaction(OrderCreateServiceRequest request) {
		return createOrderWithStockDecrease(request, stockService::decreaseWithSynchronizedAndTransaction);
	}

	@Transactional
	public OrderCreateResponse createOrderWithReentrantLockAndTransaction(OrderCreateServiceRequest request) {
		return createOrderWithStockDecrease(request, stockService::decreaseWithReentrantLockAndTransaction);
	}

	@Transactional
	public OrderCreateResponse createOrderWithOptimisticLock(OrderCreateServiceRequest request) {
		return createOrderWithStockDecrease(request, stockService::decreaseWithOptimisticLock);
	}

	@Transactional
	public OrderCreateResponse createOrderWithPessimisticLock(OrderCreateServiceRequest request) {
		return createOrderWithStockDecrease(request, stockService::decreaseWithPessimisticLock);
	}

	@Transactional
	public OrderCreateResponse createOrderWithPessimisticLockOrdered(OrderCreateServiceRequest request) {
		OrderCreateServiceRequest sortedRequest = sortItemsByProductId(request);
		return createOrderWithStockDecrease(sortedRequest, stockService::decreaseWithPessimisticLock);
	}

	@Transactional
	public OrderCreateResponse createOrderWithPessimisticLockBatch(OrderCreateServiceRequest request) {
		Member member = memberRepository.findById(request.getMemberId())
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

		Map<Long, Integer> quantitiesByProductId = mergeQuantities(request);
		// List<Long> productIds = quantitiesByProductId.keySet().stream()
		// 	.sorted()
		// 	.toList();
		List<Long> productIds = quantitiesByProductId.keySet().stream()
			.toList();

		Map<Long, Product> findProducts = productRepository.findAllById(productIds).stream()
			.collect(Collectors.toMap(Product::getId, Function.identity()));
		if (findProducts.size() != productIds.size()) {
			throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
		}

		stockService.decreaseBatchWithPessimisticLock(
			StockDecreaseBatchServiceRequest.from(quantitiesByProductId)
		);

		Order order = Order.create(member);
		for (OrderCreateItem item : request.getItems()) {
			Product product = findProducts.get(item.getProductId());
			if (product == null) {
				throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
			}
			order.addOrderItem(product, item.getQuantity());
		}

		orderRepository.save(order);

		return OrderCreateResponse.from(order);
	}

	private OrderCreateServiceRequest sortItemsByProductId(OrderCreateServiceRequest request) {
		List<OrderCreateItem> sortedItems = request.getItems().stream()
			.sorted(Comparator.comparing(OrderCreateItem::getProductId))
			.toList();

		return OrderCreateServiceRequest.builder()
			.memberId(request.getMemberId())
			.items(sortedItems)
			.build();
	}

	private Map<Long, Integer> mergeQuantities(OrderCreateServiceRequest request) {
		Map<Long, Integer> quantities = new HashMap<>();
		for (OrderCreateItem item : request.getItems()) {
			quantities.merge(item.getProductId(), item.getQuantity(), Integer::sum);
		}
		return quantities;
	}

	private OrderCreateResponse createOrderWithStockDecrease(
		OrderCreateServiceRequest request,
		BiConsumer<Long, Integer> stockDecrease
	) {
		// 회원 조회
		Member member = memberRepository.findById(request.getMemberId())
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

		Order order = Order.create(member);

		for (OrderCreateItem item : request.getItems()) {
			// 상품 조회
			Product product = productRepository.findById(item.getProductId())
				.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));

			// 재고 감소
			stockDecrease.accept(product.getId(), item.getQuantity());

			order.addOrderItem(product, item.getQuantity());
		}

		orderRepository.save(order);

		return OrderCreateResponse.from(order);
	}
}
