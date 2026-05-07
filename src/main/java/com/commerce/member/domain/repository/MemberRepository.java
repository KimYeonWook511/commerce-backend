package com.commerce.member.domain.repository;

import java.util.Optional;

import com.commerce.member.domain.Member;

public interface MemberRepository {

	Member save(Member member);

	Optional<Member> findById(Long memberId);

	Optional<Member> findByEmail(String email);

	boolean existsByEmail(String email);
}
