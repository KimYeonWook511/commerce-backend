package com.commerce.product.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;

public interface JpaProductRepository extends JpaRepository<Product, Long> {

	@Query("""
		select p
		from Product p
		where p.deletedAt is null
		and p.status in :statuses
		order by p.createdAt desc
		""")
	List<Product> findVisibleProducts(@Param("statuses") List<ProductStatus> statuses);

	@Query("""
		select p
		from Product p
		where p.id = :productId
		and p.deletedAt is null
		and p.status in :statuses
		""")
	Optional<Product> findVisibleProduct(
		@Param("productId") Long productId,
		@Param("statuses") List<ProductStatus> statuses
	);

	@Query("""
		select p
		from Product p
		where p.id = :productId
		and p.deletedAt is null
	""")
	Optional<Product> findNotDeletedProduct(@Param("productId") Long productId);
}
