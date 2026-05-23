package com.commerce.order.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.member.domain.Member;
import com.commerce.member.domain.repository.MemberRepository;
import com.commerce.member.exception.MemberErrorCode;
import com.commerce.member.exception.MemberException;
import com.commerce.order.application.command.OrderCreateCommand;
import com.commerce.order.application.command.OrderCreateItem;
import com.commerce.order.application.result.OrderCreateResult;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.product.domain.Product;
import com.commerce.product.exception.ProductErrorCode;
import com.commerce.product.exception.ProductException;
import com.commerce.product.domain.repository.ProductRepository;
import com.commerce.stock.application.StockConcurrencyService;
import com.commerce.stock.application.StockInventoryService;
import com.commerce.stock.application.command.StockDecreaseBatchCommand;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderConcurrencyService {

	private final MemberRepository memberRepository;
	private final ProductRepository productRepository;
	private final OrderRepository orderRepository;
	private final StockInventoryService stockInventoryService;
	private final StockConcurrencyService stockConcurrencyService;

	@Transactional
	public OrderCreateResult createOrderWithoutLock(OrderCreateCommand command) {
		return createOrderWithStockDecrease(command, stockConcurrencyService::decrease, "without-lock");
	}

	@Transactional
	public OrderCreateResult createOrderWithSynchronized(OrderCreateCommand command) {
		return createOrderWithStockDecrease(command, stockConcurrencyService::decreaseWithSynchronized, "synchronized");
	}

	@Transactional
	public OrderCreateResult createOrderWithSynchronizedAndTransaction(OrderCreateCommand command) {
		return createOrderWithStockDecrease(command, stockConcurrencyService::decreaseWithSynchronizedAndTransaction, "synchronized-tx");
	}

	@Transactional
	public OrderCreateResult createOrderWithReentrantLockAndTransaction(OrderCreateCommand command) {
		return createOrderWithStockDecrease(command, stockConcurrencyService::decreaseWithReentrantLockAndTransaction, "reentrant-tx");
	}

	@Transactional
	public OrderCreateResult createOrderWithOptimisticLock(OrderCreateCommand command) {
		return createOrderWithStockDecrease(command, stockConcurrencyService::decreaseWithOptimisticLock, "optimistic");
	}

	@Transactional
	public OrderCreateResult createOrderWithPessimisticLock(OrderCreateCommand command) {
		return createOrderWithStockDecrease(command, stockInventoryService::decrease, "pessimistic");
	}

	@Transactional
	public OrderCreateResult createOrderWithPessimisticLockOrdered(OrderCreateCommand command) {
		OrderCreateCommand sortedCommand = OrderCreateCommand.builder()
			.memberId(command.getMemberId())
			.idempotencyKey(command.getIdempotencyKey())
			.items(command.getItems().stream()
				.sorted((left, right) -> left.getProductId().compareTo(right.getProductId()))
				.toList())
			.build();
		return createOrderWithStockDecrease(sortedCommand, stockInventoryService::decrease, "pessimistic-ordered");
	}

	@Transactional
	public OrderCreateResult createOrderWithPessimisticLockBatch(OrderCreateCommand command) {
		Member member = memberRepository.findById(command.getMemberId())
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

		Map<Long, Integer> quantitiesByProductId = mergeQuantities(command);
		List<Long> productIds = quantitiesByProductId.keySet().stream()
			.toList();

		Map<Long, Product> productsById = productRepository.findAllById(productIds).stream()
			.collect(Collectors.toMap(Product::getId, Function.identity()));
		if (productsById.size() != productIds.size()) {
			throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
		}

		stockInventoryService.decreaseBatch(StockDecreaseBatchCommand.from(quantitiesByProductId));

		Order order = Order.create(member);
		for (OrderCreateItem item : command.getItems()) {
			Product product = productsById.get(item.getProductId());
			if (product == null) {
				throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
			}
			order.addOrderItem(product, item.getQuantity());
		}

		orderRepository.save(order);

		log.info("주문 생성 orderId={} memberId={} itemCount={} strategy=pessimistic-batch",
			order.getId(), command.getMemberId(), command.getItems().size());

		return OrderCreateResult.from(order);
	}

	private OrderCreateResult createOrderWithStockDecrease(
		OrderCreateCommand command,
		BiConsumer<Long, Integer> stockDecrease,
		String strategy
	) {
		Member member = memberRepository.findById(command.getMemberId())
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

		Order order = Order.create(member);

		for (OrderCreateItem item : command.getItems()) {
			Product product = productRepository.findById(item.getProductId())
				.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));

			stockDecrease.accept(product.getId(), item.getQuantity());
			order.addOrderItem(product, item.getQuantity());
		}

		orderRepository.save(order);

		log.info("주문 생성 orderId={} memberId={} itemCount={} strategy={}",
			order.getId(), command.getMemberId(), command.getItems().size(), strategy);

		return OrderCreateResult.from(order);
	}

	private Map<Long, Integer> mergeQuantities(OrderCreateCommand command) {
		Map<Long, Integer> quantities = new HashMap<>();
		for (OrderCreateItem item : command.getItems()) {
			quantities.merge(item.getProductId(), item.getQuantity(), Integer::sum);
		}
		return quantities;
	}
}
