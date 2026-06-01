package com.commerce.stock.domain;

import com.commerce.product.domain.Product;
import com.commerce.stock.exception.StockErrorCode;
import com.commerce.stock.exception.StockException;
import com.commerce.common.jpa.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "tbl_stock", uniqueConstraints = {
	@UniqueConstraint(name = "uk_stock_product_id", columnNames = {"product_id"})
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class Stock extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Version
	@Column(nullable = false)
	private Long version;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "product_id", nullable = false)
	private Product product;

	@Column(nullable = false)
	private int quantity;

	@Builder
	private Stock(Product product, int quantity) {
		this.product = product;
		this.quantity = quantity;
	}

	public void decrease(int quantity) {
		if (quantity <= 0) {
			throw new StockException(StockErrorCode.INVALID_DECREASE_QUANTITY);
		}

		// 재고 수량 확인이 잦아지면 메소드로 분리하기
		if (this.quantity < quantity) {
			throw new StockException(StockErrorCode.OUT_OF_STOCK);
		}

		this.quantity -= quantity;
	}

	public void increase(int quantity) {
		if (quantity <= 0) {
			throw new StockException(StockErrorCode.INVALID_INCREASE_QUANTITY);
		}

		this.quantity += quantity;
	}

}
