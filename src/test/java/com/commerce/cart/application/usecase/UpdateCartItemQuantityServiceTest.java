package com.commerce.cart.application.usecase;

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
import com.commerce.cart.application.service.UpdateCartItemQuantityProcessor;
import com.commerce.cart.presentation.http.request.CartItemUpdateRequest;

/**
 * cart phase ADR 결정 8의 retry 정책에 대한 단위 테스트.
 *
 * <p>UpdateCartItemQuantityService는 outer Service로 retry loop만 담당한다.
 * 실제 비즈니스 로직 검증은 {@link UpdateCartItemQuantityProcessorTest}에서 수행한다.
 */
@ExtendWith(MockitoExtension.class)
class UpdateCartItemQuantityServiceTest {

	@Mock
	private UpdateCartItemQuantityProcessor processor;

	@InjectMocks
	private UpdateCartItemQuantityService updateCartItemQuantityService;

	@DisplayName("정상 호출은 Processor 1회 호출로 결과를 반환한다")
	@Test
	void update_normal_callsProcessorOnce() {
		Long memberId = 1L;
		Long productId = 100L;
		CartItemUpdateRequest request = createRequest(7);
		CartItemSummaryResult expected = CartItemSummaryResult.builder()
			.productId(productId).quantity(7).build();
		given(processor.execute(memberId, productId, request)).willReturn(expected);

		CartItemSummaryResult result = updateCartItemQuantityService.update(memberId, productId, request);

		assertThat(result).isSameAs(expected);
		then(processor).should(times(1)).execute(memberId, productId, request);
	}

	@DisplayName("ObjectOptimisticLockingFailureException은 retry로 흡수된다")
	@Test
	void update_optimisticLockingFailure_retriesAndSucceeds() {
		Long memberId = 1L;
		Long productId = 100L;
		CartItemUpdateRequest request = createRequest(7);
		CartItemSummaryResult expected = CartItemSummaryResult.builder()
			.productId(productId).quantity(7).build();

		given(processor.execute(memberId, productId, request))
			.willThrow(new ObjectOptimisticLockingFailureException("cart", null))
			.willReturn(expected);

		CartItemSummaryResult result = updateCartItemQuantityService.update(memberId, productId, request);

		assertThat(result).isSameAs(expected);
		then(processor).should(times(2)).execute(memberId, productId, request);
	}

	@DisplayName("MAX_RETRY를 초과해도 충돌하면 예외를 그대로 던진다")
	@Test
	void update_optimisticLockingFailure_exceedsMaxRetry_throws() {
		Long memberId = 1L;
		Long productId = 100L;
		CartItemUpdateRequest request = createRequest(7);

		given(processor.execute(memberId, productId, request))
			.willThrow(new ObjectOptimisticLockingFailureException("cart", null));

		assertThatThrownBy(() -> updateCartItemQuantityService.update(memberId, productId, request))
			.isInstanceOf(ObjectOptimisticLockingFailureException.class);
		then(processor).should(times(3)).execute(memberId, productId, request);
	}

	private CartItemUpdateRequest createRequest(int quantity) {
		CartItemUpdateRequest request = new CartItemUpdateRequest();
		ReflectionTestUtils.setField(request, "quantity", quantity);
		return request;
	}
}
