package com.commerce.order.application.usecase;

import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.commerce.common.exception.CommonErrorCode;
import com.commerce.common.exception.CommonException;
import com.commerce.order.application.dto.OrderCreateCommand;
import com.commerce.order.application.dto.OrderCreateResult;
import com.commerce.order.application.port.OrderIdempotencyStore;
import com.commerce.order.application.service.OrderCreateService;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.order.infrastructure.OrderIdempotencyStoreUnavailableException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreateUseCase {

	private final OrderRepository orderRepository;
	private final OrderIdempotencyStore orderIdempotencyStore;
	private final OrderCreateService orderCreateService;

	@Value("${order.idempotency.ttl-seconds:60}")
	private long idempotencyTtlSeconds;

	public OrderCreateResult createOrder(OrderCreateCommand command) {
		if (!StringUtils.hasText(command.getIdempotencyKey())) {
			throw new CommonException(CommonErrorCode.INVALID_REQUEST);
		}

		Long memberId = command.getMemberId();
		String idempotencyKey = command.getIdempotencyKey();
		Duration ttl = Duration.ofSeconds(idempotencyTtlSeconds);

		boolean reserved;
		try {
			reserved = orderIdempotencyStore.reserve(memberId, idempotencyKey, ttl);
		} catch (OrderIdempotencyStoreUnavailableException e) {
			// Redis 장애로 in-flight 차단을 못 한 경우, DB unique 제약 안전망 경로로 fallback.
			// marker 가 생성되지 않았으므로 clear 호출하지 않는다.
			log.warn("멱등성 캐시 장애, DB unique 제약으로 fallback: memberId={}, key={}", memberId, idempotencyKey);
			return findOrExecute(command, memberId, idempotencyKey);
		}

		if (!reserved) {
			throw new OrderException(OrderErrorCode.ORDER_IDEMPOTENCY_IN_PROGRESS);
		}

		try {
			return findOrExecute(command, memberId, idempotencyKey);
		} finally {
			// UseCase 계층(tx 없음)이므로 finally 는 orderCreateService tx 종료 후에 실행된다.
			orderIdempotencyStore.clear(memberId, idempotencyKey);
		}
	}

	private OrderCreateResult findOrExecute(OrderCreateCommand command, Long memberId, String idempotencyKey) {
		// Redis 만료 후 정당한 재요청을 멱등 흡수하기 위한 DB 사전 체크.
		// reserve 성공(in-flight 차단) 이후 find 를 수행하므로 캐시의 DB 도달 전 차단 가치를 보존한다.
		Optional<Order> existing = orderRepository.findByMemberIdAndIdempotencyKey(memberId, idempotencyKey);
		if (existing.isPresent()) {
			log.info("주문 멱등 응답 orderId={} memberId={} source=db idempotencyKey={}",
				existing.get().getId(), memberId, idempotencyKey);
			return OrderCreateResult.from(existing.get());
		}
		return orderCreateService.execute(command);
	}
}
