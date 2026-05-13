package com.commerce.outbox.infrastructure;

import org.springframework.stereotype.Repository;

import com.commerce.outbox.domain.ProcessedEvent;
import com.commerce.outbox.domain.repository.ProcessedEventRepository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class ProcessedEventRepositoryAdapter implements ProcessedEventRepository {

	private final JpaProcessedEventRepository jpaProcessedEventRepository;

	@Override
	public ProcessedEvent save(ProcessedEvent processedEvent) {
		return jpaProcessedEventRepository.saveAndFlush(processedEvent); // 바로 commit은 안 되지만 유니크 인덱스 점유 (동일 키 insert는 lock 대기)
	}
}
