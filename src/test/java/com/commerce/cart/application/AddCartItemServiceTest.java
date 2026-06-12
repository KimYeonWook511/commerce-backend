package com.commerce.cart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.cart.application.result.CartItemSummaryResult;
import com.commerce.cart.presentation.http.request.CartItemAddRequest;

/**
 * cart phase ADR 결정 8의 retry 정책에 대한 단위 테스트.
 *
 * <p>AddCartItemService는 outer Service로 retry loop만 담당한다.
 * 실제 비즈니스 로직 검증은 {@link AddCartItemProcessorTest}에서 수행한다.
 */
@ExtendWith(MockitoExtension.class)
class AddCartItemServiceTest {

	@Mock
	private AddCartItemProcessor processor;

	@InjectMocks
	private AddCartItemService addCartItemService;

	@DisplayName("정상 호출은 Processor 1회 호출로 결과를 반환한다")
	@Test
	void add_normal_callsProcessorOnce() {
		Long memberId = 1L;
		CartItemAddRequest request = createRequest(100L, 3);
		CartItemSummaryResult expected = CartItemSummaryResult.builder()
			.productId(100L).quantity(3).build();
		given(processor.execute(memberId, request)).willReturn(expected);

		CartItemSummaryResult result = addCartItemService.add(memberId, request);

		assertThat(result).isSameAs(expected);
		then(processor).should(times(1)).execute(memberId, request);
	}

	@DisplayName("ObjectOptimisticLockingFailureException은 retry로 흡수된다")
	@Test
	void add_optimisticLockingFailure_retriesAndSucceeds() {
		Long memberId = 1L;
		CartItemAddRequest request = createRequest(100L, 3);
		CartItemSummaryResult expected = CartItemSummaryResult.builder()
			.productId(100L).quantity(3).build();

		given(processor.execute(memberId, request))
			.willThrow(new ObjectOptimisticLockingFailureException("cart", null))
			.willReturn(expected);

		CartItemSummaryResult result = addCartItemService.add(memberId, request);

		assertThat(result).isSameAs(expected);
		then(processor).should(times(2)).execute(memberId, request);
	}

	@DisplayName("MAX_RETRY를 초과해도 충돌하면 예외를 그대로 던진다")
	@Test
	void add_optimisticLockingFailure_exceedsMaxRetry_throws() {
		Long memberId = 1L;
		CartItemAddRequest request = createRequest(100L, 3);

		given(processor.execute(memberId, request))
			.willThrow(new ObjectOptimisticLockingFailureException("cart", null));

		assertThatThrownBy(() -> addCartItemService.add(memberId, request))
			.isInstanceOf(ObjectOptimisticLockingFailureException.class);
		then(processor).should(times(3)).execute(memberId, request);
	}

	private CartItemAddRequest createRequest(Long productId, int quantity) {
		CartItemAddRequest request = new CartItemAddRequest();
		ReflectionTestUtils.setField(request, "productId", productId);
		ReflectionTestUtils.setField(request, "quantity", quantity);
		return request;
	}
}
