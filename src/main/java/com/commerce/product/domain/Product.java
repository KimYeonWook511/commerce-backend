package com.commerce.product.domain;

import java.time.LocalDateTime;

import com.commerce.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "tbl_product")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class Product extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private int price;

	private String description;

	private String imageUrl;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false)
	private ProductStatus status;

	private LocalDateTime deletedAt;

	@Builder
	private Product(String name, int price, String description, String imageUrl, ProductStatus status) {
		validateName(name);
		validatePrice(price);
		validateStatus(status);

		this.name = name;
		this.price = price;
		this.description = description;
		this.imageUrl = imageUrl;
		this.status = status;
	}

	public void update(String name, int price, String description, String imageUrl, ProductStatus status) {
		validateName(name);
		validatePrice(price);
		validateStatus(status);

		this.name = name;
		this.price = price;
		this.description = description;
		this.imageUrl = imageUrl;
		this.status = status;
	}

	public void softDelete() {
		this.deletedAt = LocalDateTime.now();
	}

	public boolean isPubliclyVisible() {
		return deletedAt == null && status.isPubliclyVisible();
	}

	private void validateName(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Product name is required.");
		}
	}

	private void validatePrice(int price) {
		if (price <= 0) {
			throw new IllegalArgumentException("Product price must be greater than 0.");
		}
	}

	private void validateStatus(ProductStatus status) {
		if (status == null) {
			throw new IllegalArgumentException("Product status is required.");
		}
	}

}
