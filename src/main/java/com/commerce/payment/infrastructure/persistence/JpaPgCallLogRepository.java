package com.commerce.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import com.commerce.payment.domain.PgCallLog;

public interface JpaPgCallLogRepository extends JpaRepository<PgCallLog, Long> {
}
