package com.commerce.order.application;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.commerce.common.exception.CommonErrorCode;
import com.commerce.common.exception.CommonException;
import com.commerce.order.application.command.OrderCreateCommand;
import com.commerce.order.application.result.OrderCreateResult;
import com.commerce.order.application.port.OrderIdempotencyStore;
import com.commerce.order.domain.repository.OrderRepository;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;

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

	@Value("${order.idempotency.ttl-seconds:600}")
	private long idempotencyTtlSeconds;

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public OrderCreateResult createOrder(OrderCreateCommand command) {
		if (!StringUtils.hasText(command.getIdempotencyKey())) {
			throw new CommonException(CommonErrorCode.INVALID_REQUEST);
		}

		Long memberId = command.getMemberId();
		String idempotencyKey = command.getIdempotencyKey();
		Duration ttl = Duration.ofSeconds(idempotencyTtlSeconds);
		boolean reserved = orderIdempotencyStore.reserve(memberId, idempotencyKey, ttl);

		if (!reserved) {
			return orderIdempotencyStore.getCompletedOrderId(memberId, idempotencyKey)
				.map(orderId -> orderRepository.findById(orderId)
					.map(OrderCreateResult::from)
					.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND)))
				.orElseGet(() -> attemptCreateOrder(command, memberId, idempotencyKey, ttl));
		}

		return attemptCreateOrder(command, memberId, idempotencyKey, ttl);
	}

	private OrderCreateResult attemptCreateOrder(
		OrderCreateCommand command, Long memberId, String idempotencyKey, Duration ttl
	) {
		try {
			return orderCreateProcessor.execute(command, ttl);
		} catch (DataIntegrityViolationException ex) {
			// unique 위반 — 동시 요청 or TTL 만료 후 중복: Application 계층에서 처리하고 Presentation으로 넘기지 않는다.
			return orderRepository.findByMemberIdAndIdempotencyKey(memberId, idempotencyKey)
				.map(order -> {
					// 이후 동일 키 재요청이 Redis에서 바로 처리되도록 complete 상태로 갱신한다.
					orderIdempotencyStore.complete(memberId, idempotencyKey, order.getId(), ttl);
					return OrderCreateResult.from(order);
				})
				.orElseGet(() -> {
					log.error("멱등키 충돌이 아닌 unique 제약 위반 발생: {}", ex.getMessage());
					throw new OrderException(OrderErrorCode.ORDER_NOT_FOUND);
				});
		} catch (RuntimeException ex) {
			orderIdempotencyStore.clear(memberId, idempotencyKey);
			throw ex;
		}
	}
}
