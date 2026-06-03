package com.commerce.order.infrastructure;

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
		where o.merchantPayKey = :merchantPayKey
		and o.memberId = :memberId
		""")
	Optional<Order> findByMerchantPayKeyAndMemberId(
		@Param("merchantPayKey") String merchantPayKey,
		@Param("memberId") Long memberId
	);

	Optional<Order> findByMerchantPayKey(String merchantPayKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select o
		from Order o
		where o.merchantPayKey = :merchantPayKey
		""")
	Optional<Order> findByMerchantPayKeyForUpdate(@Param("merchantPayKey") String merchantPayKey);

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
