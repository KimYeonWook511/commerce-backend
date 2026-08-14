package com.commerce.payment.infrastructure.persistence;

import org.springframework.stereotype.Repository;

import com.commerce.payment.domain.PgCallLog;
import com.commerce.payment.domain.repository.PgCallLogRepository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class PgCallLogRepositoryAdapter implements PgCallLogRepository {

	private final JpaPgCallLogRepository jpaPgCallLogRepository;

	// 낙관 락이 없고 판정에도 쓰이지 않아 이 저장은 아무 순서에도 기대지 않는다.
	@Override
	public PgCallLog save(PgCallLog pgCallLog) {
		return jpaPgCallLogRepository.save(pgCallLog);
	}
}
