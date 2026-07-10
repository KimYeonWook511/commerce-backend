package com.commerce.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.member.domain.Member;
import com.commerce.member.domain.repository.MemberRepository;
import com.commerce.member.domain.exception.MemberErrorCode;
import com.commerce.member.domain.exception.MemberException;
import com.commerce.order.application.dto.OrderCreateCommand;
import com.commerce.order.application.dto.OrderCreateItem;
import com.commerce.order.application.port.CartItemRemover;
import com.commerce.order.application.dto.OrderCreateResult;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.domain.repository.ProductRepository;
import com.commerce.product.domain.exception.ProductErrorCode;
import com.commerce.product.domain.exception.ProductException;
import com.commerce.stock.application.service.DecreaseStockService;
import com.commerce.stock.domain.exception.StockErrorCode;
import com.commerce.stock.domain.exception.StockException;

@ExtendWith(MockitoExtension.class)
class CreateOrderServiceTest {

	@Mock
	private MemberRepository memberRepository;

	@Mock
	private ProductRepository productRepository;

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private DecreaseStockService decreaseStockService;

	@Mock
	private CartItemRemover cartItemRemover;

	@InjectMocks
	private CreateOrderService createOrderService;

	@DisplayName("유효한 요청이면 주문을 생성한다")
	@Test
	void execute_whenValidRequest_createOrder() {
		// given
		Member member = createMember(1L);
		Product product = createProduct(10L, "product-1", 1000);
		OrderCreateCommand command = createCommand(1L, "idem-key",
			List.of(OrderCreateItem.builder().productId(10L).quantity(2).build()));

		given(memberRepository.findById(1L)).willReturn(Optional.of(member));
		given(productRepository.findAllById(List.of(10L))).willReturn(List.of(product));
		given(orderRepository.save(any(Order.class))).willAnswer(inv -> {
			Order order = inv.getArgument(0);
			ReflectionTestUtils.setField(order, "id", 100L);
			return order;
		});

		// when
		OrderCreateResult result = createOrderService.execute(command);

		// then
		assertThat(result.getOrderId()).isEqualTo(100L);
		then(decreaseStockService).should().decrease(10L, 2);
		then(cartItemRemover).should().removeByMemberAndProducts(1L, List.of(10L));
	}

	@DisplayName("회원이 존재하지 않으면 예외를 던진다")
	@Test
	void execute_whenMemberNotFound_throwException() {
		// given
		OrderCreateCommand command = createCommand(1L, "idem-key",
			List.of(OrderCreateItem.builder().productId(10L).quantity(2).build()));
		given(memberRepository.findById(1L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> createOrderService.execute(command))
			.isInstanceOf(MemberException.class)
			.satisfies(ex -> assertThat(((MemberException)ex).getErrorCode())
				.isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));
	}

	@DisplayName("상품 일부가 존재하지 않으면 예외를 던진다")
	@Test
	void execute_whenProductPartiallyNotFound_throwException() {
		// given
		Member member = createMember(1L);
		OrderCreateCommand command = createCommand(1L, "idem-key", List.of(
			OrderCreateItem.builder().productId(10L).quantity(2).build(),
			OrderCreateItem.builder().productId(99L).quantity(1).build()
		));
		given(memberRepository.findById(1L)).willReturn(Optional.of(member));
		given(productRepository.findAllById(anyList()))
			.willReturn(List.of(createProduct(10L, "product-1", 1000)));

		// when & then
		assertThatThrownBy(() -> createOrderService.execute(command))
			.isInstanceOf(ProductException.class)
			.satisfies(ex -> assertThat(((ProductException)ex).getErrorCode())
				.isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND));
	}

	@DisplayName("재고 차감에 실패하면 예외를 전파한다")
	@Test
	void execute_whenStockDecreaseFails_propagateException() {
		// given
		Member member = createMember(1L);
		Product product = createProduct(10L, "product-1", 1000);
		OrderCreateCommand command = createCommand(1L, "idem-key",
			List.of(OrderCreateItem.builder().productId(10L).quantity(2).build()));

		given(memberRepository.findById(1L)).willReturn(Optional.of(member));
		given(productRepository.findAllById(List.of(10L))).willReturn(List.of(product));
		willThrow(new StockException(StockErrorCode.STOCK_NOT_FOUND))
			.given(decreaseStockService).decrease(10L, 2);

		// when & then
		assertThatThrownBy(() -> createOrderService.execute(command))
			.isInstanceOf(StockException.class)
			.satisfies(ex -> assertThat(((StockException)ex).getErrorCode())
				.isEqualTo(StockErrorCode.STOCK_NOT_FOUND));
	}

	private Member createMember(Long id) {
		Member member = Member.createUser("test@example.com", "password123", "user1");
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private Product createProduct(Long id, String name, int price) {
		Product product = Product.create(name, price, null, null, ProductStatus.ON_SALE);
		ReflectionTestUtils.setField(product, "id", id);
		return product;
	}

	private OrderCreateCommand createCommand(Long memberId, String idempotencyKey, List<OrderCreateItem> items) {
		return OrderCreateCommand.builder()
			.memberId(memberId)
			.idempotencyKey(idempotencyKey)
			.items(items)
			.build();
	}
}
