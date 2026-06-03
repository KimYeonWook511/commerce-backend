package com.commerce.order.application;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.member.domain.repository.MemberRepository;
import com.commerce.member.exception.MemberErrorCode;
import com.commerce.member.exception.MemberException;
import com.commerce.order.application.command.OrderCreateCommand;
import com.commerce.order.application.command.OrderCreateItem;
import com.commerce.order.application.port.CartItemRemover;
import com.commerce.order.application.result.OrderCreateResult;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.repository.ProductRepository;
import com.commerce.product.exception.ProductErrorCode;
import com.commerce.product.exception.ProductException;
import com.commerce.stock.application.StockInventoryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreateProcessor {

	private final MemberRepository memberRepository;
	private final ProductRepository productRepository;
	private final OrderRepository orderRepository;
	private final StockInventoryService stockInventoryService;
	private final CartItemRemover cartItemRemover;

	@Transactional
	public OrderCreateResult execute(OrderCreateCommand command) {
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
		memberRepository.findById(command.getMemberId())
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

		List<Long> productIds = extractDistinctProductIds(command);
		Map<Long, Product> productsById = productRepository.findAllById(productIds).stream()
			.collect(Collectors.toMap(Product::getId, Function.identity()));
		if (productsById.size() != productIds.size()) {
			throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
		}

		Order order = Order.create(command.getMemberId(), command.getIdempotencyKey());

		for (OrderCreateItem item : command.getItems()) {
			Product product = productsById.get(item.getProductId());
			if (product == null) {
				throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
			}

			stockInventoryService.decrease(product.getId(), item.getQuantity());
			order.addOrderItem(product.getId(), item.getQuantity(), product.getPrice());
		}

		orderRepository.save(order);

		cartItemRemover.removeByMemberAndProducts(command.getMemberId(), productIds);

		log.info("주문 생성 orderId={} memberId={} itemCount={}",
			order.getId(), command.getMemberId(), command.getItems().size());

		return OrderCreateResult.from(order);
	}

	private List<Long> extractDistinctProductIds(OrderCreateCommand command) {
		return command.getItems().stream()
			.map(OrderCreateItem::getProductId)
			.distinct()
			.toList();
	}
}
