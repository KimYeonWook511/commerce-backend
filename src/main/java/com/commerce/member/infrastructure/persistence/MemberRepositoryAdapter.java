package com.commerce.member.infrastructure.persistence;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.commerce.member.domain.Member;
import com.commerce.member.domain.repository.MemberRepository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class MemberRepositoryAdapter implements MemberRepository {

	private final JpaMemberRepository jpaMemberRepository;

	@Override
	public Member save(Member member) {
		return jpaMemberRepository.saveAndFlush(member);
	}

	@Override
	public Optional<Member> findById(Long memberId) {
		return jpaMemberRepository.findById(memberId);
	}

	@Override
	public Optional<Member> findByEmail(String email) {
		return jpaMemberRepository.findByEmail(email);
	}

	@Override
	public boolean existsByEmail(String email) {
		return jpaMemberRepository.existsByEmail(email);
	}
}
