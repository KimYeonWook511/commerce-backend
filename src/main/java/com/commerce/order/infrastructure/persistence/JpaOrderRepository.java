package com.commerce.order.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;

public interface JpaOrderRepository extends JpaRepository<Order, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select o
		from Order o
		where o.id = :id
		""")
	Optional<Order> findByIdForUpdate(@Param("id") Long id);

	// 주문 행 하나만 잠근다(단일 행 락). orderItems는 호출처에서 aggregate를 통해 lazy 로드한다.
	// join fetch + FOR UPDATE는 락이 자식(order_item)까지 plan·인덱스 순서로 번지고 distinct+FOR UPDATE의
	// SQL 거동 의존을 낳으므로 쓰지 않는다(ADR-L6, 데드락 검증 후속 #259).
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select o from Order o
		where o.id = :orderId
		and o.memberId = :memberId
		""")
	Optional<Order> findByIdAndMemberIdForUpdate(
		@Param("orderId") Long orderId,
		@Param("memberId") Long memberId
	);

	@Query("""
		select o
		from Order o
		where o.id = :orderId
		and o.memberId = :memberId
		""")
	Optional<Order> findByIdAndMemberId(@Param("orderId") Long orderId, @Param("memberId") Long memberId);

	@Query("""
		select distinct o from Order o
		join fetch o.orderItems oi
		where o.id = :orderId
		and o.memberId = :memberId
		""")
	Optional<Order> findByIdAndMemberIdWithItems(@Param("orderId") Long orderId, @Param("memberId") Long memberId);

	@Query("""
		select distinct o from Order o
		join fetch o.orderItems oi
		where o.id = :orderId
		""")
	Optional<Order> findByIdWithItems(@Param("orderId") Long orderId);

	@Query("""
		select o
		from Order o
		where o.status = :status
		and o.createdAt < :cutoff
		and o.id > :lastId
		order by o.id asc
		""")
	List<Order> findExpiredOrdersAfterId(
		@Param("status") OrderStatus status,
		@Param("cutoff") LocalDateTime cutoff,
		@Param("lastId") Long lastId,
		Pageable pageable
	);

	@Query("""
		select o
		from Order o
		where o.memberId = :memberId
		and o.idempotencyKey = :idempotencyKey
		""")
	Optional<Order> findByMemberIdAndIdempotencyKey(
		@Param("memberId") Long memberId,
		@Param("idempotencyKey") String idempotencyKey
	);

}
