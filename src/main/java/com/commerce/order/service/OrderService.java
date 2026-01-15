package com.commerce.order.service;

import java.util.function.BiConsumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.member.domain.Member;
import com.commerce.member.exception.MemberErrorCode;
import com.commerce.member.exception.MemberException;
import com.commerce.member.repository.MemberRepository;
import com.commerce.order.domain.Order;
import com.commerce.order.repository.OrderRepository;
import com.commerce.order.service.request.OrderCreateItem;
import com.commerce.order.service.request.OrderCreateServiceRequest;
import com.commerce.order.service.response.OrderCreateResponse;
import com.commerce.product.domain.Product;
import com.commerce.product.exception.ProductErrorCode;
import com.commerce.product.exception.ProductException;
import com.commerce.product.repository.ProductRepository;
import com.commerce.stock.service.StockService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

	private final MemberRepository memberRepository;
	private final ProductRepository productRepository;
	private final OrderRepository orderRepository;
	private final StockService stockService;

	@Transactional
	public OrderCreateResponse createOrder(OrderCreateServiceRequest request) {
		return createOrderWithPessimisticLock(request);
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
