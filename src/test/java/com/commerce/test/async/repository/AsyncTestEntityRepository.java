package com.commerce.test.async.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.commerce.test.async.domain.AsyncTestEntity;

public interface AsyncTestEntityRepository extends JpaRepository<AsyncTestEntity, Long> {
}
