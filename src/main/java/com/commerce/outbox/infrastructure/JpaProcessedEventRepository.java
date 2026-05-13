package com.commerce.outbox.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import com.commerce.outbox.domain.ProcessedEvent;

public interface JpaProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {
}
