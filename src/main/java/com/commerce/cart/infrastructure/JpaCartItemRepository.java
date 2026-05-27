package com.commerce.cart.infrastructure;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.commerce.cart.domain.CartItem;

public interface JpaCartItemRepository extends JpaRepository<CartItem, Long> {

	Optional<CartItem> findByMemberIdAndProductId(Long memberId, Long productId);

	List<CartItem> findAllByMemberIdOrderByCreatedAtDesc(Long memberId);

	@Modifying
	@Query("""
		delete from CartItem c
		where c.memberId = :memberId
		and c.productId = :productId
		""")
	void deleteByMemberIdAndProductId(@Param("memberId") Long memberId, @Param("productId") Long productId);

	@Modifying
	@Query("""
		delete from CartItem c
		where c.memberId = :memberId
		and c.productId in :productIds
		""")
	void deleteByMemberIdAndProductIdIn(
		@Param("memberId") Long memberId,
		@Param("productIds") List<Long> productIds
	);
}
