package com.commerce.test.lock.repository;

import com.commerce.test.lock.domain.LockMember;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LockMemberRepository extends JpaRepository<LockMember, Long> {
}
