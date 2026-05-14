package com.commerce.member.infrastructure.persistence.support;

import org.springframework.boot.test.context.TestComponent;

import com.commerce.member.domain.Member;
import com.commerce.member.infrastructure.JpaMemberRepository;

import lombok.RequiredArgsConstructor;
import support.CleanupOrder;
import support.PersistenceTestSupport;

@TestComponent
@RequiredArgsConstructor
public class MemberPersistenceTestSupport implements PersistenceTestSupport {

	private final JpaMemberRepository memberRepository;

	@Override
	public CleanupOrder cleanupOrder() {
		return CleanupOrder.MEMBER;
	}

	@Override
	public void deleteAllInBatch() {
		memberRepository.deleteAllInBatch();
	}

	public Member save(Member member) {
		return memberRepository.save(member);
	}
}
