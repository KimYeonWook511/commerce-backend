package com.commerce.order.application;

import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.commerce.common.exception.CommonErrorCode;
import com.commerce.common.exception.CommonException;
import com.commerce.order.application.command.OrderCreateCommand;
import com.commerce.order.application.result.OrderCreateResult;
import com.commerce.order.application.port.OrderIdempotencyStore;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.order.exception.OrderIdempotencyStoreUnavailableException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderCreateService {

	private final OrderRepository orderRepository;
	private final OrderIdempotencyStore orderIdempotencyStore;
	private final OrderCreateProcessor orderCreateProcessor;

	@Value("${order.idempotency.ttl-seconds:60}")
	private long idempotencyTtlSeconds;

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
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
			// NOT_SUPPORTED 이므로 finally 가 commit 이후에 호출됨 (ADR-005 정합).
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
		return orderCreateProcessor.execute(command);
	}
}
