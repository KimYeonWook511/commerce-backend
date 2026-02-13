package com.commerce.order.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;

public interface OrderRepository extends JpaRepository<Order, Long> {

	@Query("""
		select distinct o from Order o
		join fetch o.member m
		join fetch o.orderItems oi
		join fetch oi.product p
		where o.id = :orderId
		and m.id = :memberId
		""")
	Optional<Order> findByIdAndMemberIdWithItems(@Param("orderId") Long orderId, @Param("memberId") Long memberId);

	@Query("""
		select distinct o from Order o
		join fetch o.member m
		join fetch o.orderItems oi
		join fetch oi.product p
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

}
