package com.commerce.test.async.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Table(name = "async_test")
@Getter
public class AsyncTestEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private AsyncTestStatus status;

	@Column(nullable = false, length = 100)
	private String firstThreadName;

	@Column(nullable = false, length = 50)
	private String firstTxName;

	@Column(length = 100)
	private String asyncThreadName;

	@Column(length = 50)
	private String asyncTxName;

	protected AsyncTestEntity() {
	}

	private AsyncTestEntity(String name, String firstThreadName, String firstTxName) {
		this.name = name;
		this.status = AsyncTestStatus.REQUESTED;
		this.firstThreadName = firstThreadName;
		this.firstTxName = firstTxName;
	}

	public static AsyncTestEntity requested(String name, String firstThreadName, String firstTxName) {
		return new AsyncTestEntity(name, firstThreadName, firstTxName);
	}

	public void markSucceeded(String asyncThreadName, String asyncTxName) {
		this.status = AsyncTestStatus.SUCCEEDED;
		this.asyncThreadName = asyncThreadName;
		this.asyncTxName = asyncTxName;
	}

	public void markFailed(String asyncThreadName, String asyncTxName) {
		this.status = AsyncTestStatus.FAILED;
		this.asyncThreadName = asyncThreadName;
		this.asyncTxName = asyncTxName;
	}

}
