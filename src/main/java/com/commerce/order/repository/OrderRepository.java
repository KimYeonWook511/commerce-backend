package com.commerce.order.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.commerce.order.domain.Order;

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

}
