package com.commerce.payment.infrastructure.persistence.support;

import java.util.List;

import org.springframework.boot.test.context.TestComponent;

import com.commerce.payment.domain.PgCallLog;
import com.commerce.payment.infrastructure.persistence.JpaPgCallLogRepository;
import com.commerce.support.CleanupOrder;
import com.commerce.support.PersistenceTestSupport;

import lombok.RequiredArgsConstructor;

@TestComponent
@RequiredArgsConstructor
public class PgCallLogPersistenceTestSupport implements PersistenceTestSupport {

	private final JpaPgCallLogRepository jpaPgCallLogRepository;

	@Override
	public CleanupOrder cleanupOrder() {
		return CleanupOrder.PG_CALL_LOG;
	}

	@Override
	public void deleteAllInBatch() {
		jpaPgCallLogRepository.deleteAllInBatch();
	}

	public List<PgCallLog> findAll() {
		return jpaPgCallLogRepository.findAll();
	}
}
