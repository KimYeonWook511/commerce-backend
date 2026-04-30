package com.commerce.stock.domain;

import com.commerce.common.jpa.BaseTimeEntity;
import com.commerce.stock.exception.StockErrorCode;
import com.commerce.stock.exception.StockException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "tbl_stock_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class StockHistory extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "stock_id", nullable = false)
	private Stock stock;

	@Column(nullable = false)
	private int quantityChange;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private StockAdjustmentReason reason;

	@Column(nullable = false)
	private Long adminMemberId;

	@Builder
	private StockHistory(Stock stock, int quantityChange, StockAdjustmentReason reason, Long adminMemberId) {
		validateQuantityChange(quantityChange);

		this.stock = stock;
		this.quantityChange = quantityChange;
		this.reason = reason;
		this.adminMemberId = adminMemberId;
	}

	private void validateQuantityChange(int quantityChange) {
		if (quantityChange == 0) {
			throw new StockException(StockErrorCode.INVALID_HISTORY_QUANTITY_CHANGE);
		}
	}

}
