package com.commerce.stock.domain;

import com.commerce.common.jpa.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
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

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "stock_id", nullable = false, foreignKey = @ForeignKey(name = "fk_stock_history_stock_id"))
	private Stock stock;

	@Column(nullable = false)
	private int quantityChange;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false)
	private StockAdjustmentReason reason;

	@Column(nullable = false)
	private Long adminMemberId;

	@Builder
	private StockHistory(Stock stock, int quantityChange, StockAdjustmentReason reason, Long adminMemberId) {
		this.stock = stock;
		this.quantityChange = quantityChange;
		this.reason = reason;
		this.adminMemberId = adminMemberId;
	}

}
