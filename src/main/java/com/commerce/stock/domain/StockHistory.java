package com.commerce.stock.domain;

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
@Table(name = "tbl_stock_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class StockHistory extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "stock_id", nullable = false)
	private Long stockId;

	@Column(nullable = false)
	private int quantityChange;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false)
	private StockAdjustmentReason reason;

	@Column(nullable = false)
	private Long adminMemberId;

	@Builder
	private StockHistory(Long stockId, int quantityChange, StockAdjustmentReason reason, Long adminMemberId) {
		this.stockId = stockId;
		this.quantityChange = quantityChange;
		this.reason = reason;
		this.adminMemberId = adminMemberId;
	}

}
